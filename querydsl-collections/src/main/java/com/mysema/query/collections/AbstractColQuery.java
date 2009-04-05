/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.collections;

import static com.mysema.query.collections.utils.QueryIteratorUtils.multiArgFilter;
import static com.mysema.query.collections.utils.QueryIteratorUtils.toArrayIterator;
import static com.mysema.query.collections.utils.QueryIteratorUtils.transform;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

import org.apache.commons.collections15.IteratorUtils;
import org.apache.commons.collections15.iterators.IteratorChain;

import com.mysema.query.JoinExpression;
import com.mysema.query.Projectable;
import com.mysema.query.QueryBaseWithProjection;
import com.mysema.query.collections.eval.Evaluator;
import com.mysema.query.collections.iterators.FilteringMultiIterator;
import com.mysema.query.collections.iterators.MultiIterator;
import com.mysema.query.collections.support.DefaultIndexSupport;
import com.mysema.query.collections.support.DefaultSourceSortingSupport;
import com.mysema.query.collections.support.MultiComparator;
import com.mysema.query.collections.support.SimpleIteratorSource;
import com.mysema.query.collections.utils.EvaluatorUtils;
import com.mysema.query.grammar.JavaOps;
import com.mysema.query.grammar.Ops;
import com.mysema.query.grammar.Order;
import com.mysema.query.grammar.OrderSpecifier;
import com.mysema.query.grammar.types.Expr;
import com.mysema.query.grammar.types.Operation;
import com.mysema.query.grammar.types.Expr.EBoolean;
import com.mysema.query.util.CloseableIterator;

/**
 * AbstractColQuery provides a base class for Collection query implementations.
 * Extend it like this
 * 
 * <pre>
 * public class MyType extends AbstractColQuery&lt;MyType&gt;{
 *   ...
 * }
 * </pre>
 * 
 * @see ColQuery
 * 
 * @author tiwe
 * @version $Id$
 */
// TODO : implement leftJoin, rightJoin and fullJoin
// TODO : implement groupBy and having
public class AbstractColQuery<SubType extends AbstractColQuery<SubType>> extends QueryBaseWithProjection<Object, SubType> implements Closeable, Projectable{

    @SuppressWarnings("unchecked")
    private final SubType _this = (SubType) this;

    private boolean closed = false;

    private final Map<Expr<?>, Iterable<?>> exprToIt = new HashMap<Expr<?>, Iterable<?>>();

    private QueryIndexSupport indexSupport;

    private final JavaOps ops;

    /**
     * turn OR queries into sequential UNION queries
     */
    private boolean sequentialUnion = false;

    /**
     * optimize sort order for optimal index usage
     */
    private boolean sortSources = true;

    private SourceSortingSupport sourceSortingSupport;

    /**
     * wrap single source iterators to avoid cartesian view
     */
    private boolean wrapIterators = true;

    public AbstractColQuery() {
        this(JavaOps.DEFAULT);
    }

    public AbstractColQuery(JavaOps ops) {
        this.ops = ops;
        this.sourceSortingSupport = new DefaultSourceSortingSupport();
    }

    protected <A> SubType alias(Expr<A> path, Iterable<? extends A> col) {
        exprToIt.put(path, col);
        return _this;
    }
    
    private boolean changeToUnionQuery(EBoolean condition) {
        return sequentialUnion && condition instanceof Operation 
            && ((Operation<?, ?>) condition).getOperator() == Ops.OR;
    }

    private void checkClosed() {
        if (closed) throw new IllegalStateException("Already closed");
        closed = true;
    }

    /**
     * Close the Query and related datasource connection
     */
    public void close() {
        // overwrite
    }

    public long count() {
        try {
            List<Expr<?>> sources = new ArrayList<Expr<?>>();
            Iterator<?> it;
            if (getMetadata().getJoins().size() == 1) {
                it = handleFromWhereSingleSource(sources);
            } else {
                it = handleFromWhereMultiSource(sources);
            }
            long count = 0l;
            while (it.hasNext()) {
                it.next();
                count++;
            }
            return count;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected QueryIndexSupport createIndexSupport(Map<Expr<?>, Iterable<?>> exprToIt, JavaOps ops,List<Expr<?>> sources) {
        return new DefaultIndexSupport(new SimpleIteratorSource(exprToIt), ops, sources);
    }

    private <RT> Iterator<RT> createIterator(Expr<RT> projection) throws Exception {
        checkClosed();
        List<Expr<?>> sources = new ArrayList<Expr<?>>();
        // from / where
        Iterator<?> it;
        if (getMetadata().getJoins().size() == 1) {
            it = handleFromWhereSingleSource(sources);
        } else {
            it = handleFromWhereMultiSource(sources);
        }

        if (it.hasNext()) {
            // order
            if (!getMetadata().getOrderBy().isEmpty()) {
                it = handleOrderBy(sources, it);
            }

            // select
            return handleSelect(it, sources, projection);

        } else {
            return Collections.<RT> emptyList().iterator();
        }

    }
    
    private Iterator<Object[]> createMultiIterator(List<Expr<?>> sources, EBoolean condition) {
        MultiIterator multiIt;
        if (condition == null || !wrapIterators) {
            multiIt = new MultiIterator();
        } else {
            multiIt = new FilteringMultiIterator(ops, condition);
        }
        for (Expr<?> expr : sources)
            multiIt.add(expr);
        multiIt.init(indexSupport.getChildFor(condition));

        if (condition != null) {
            return multiArgFilter(ops, multiIt, sources, condition);
        } else {
            return multiIt;
        }
    }
    
    public <A> SubType from(Expr<A> entity, A first, A... rest) {
        List<A> list = new ArrayList<A>(rest.length + 1);
        list.add(first);
        list.addAll(Arrays.asList(rest));
        return from(entity, list);
    }

    public <A> SubType from(Expr<A> entity, Iterable<? extends A> col) {
        alias(entity, col);
        from((Expr<?>) entity);
        return _this;
    }

    protected Iterator<?> handleFromWhereMultiSource(List<Expr<?>> sources) throws Exception {
        EBoolean condition = getMetadata().getWhere();
        if (sortSources) {
            sourceSortingSupport.sortSources(getMetadata().getJoins(), condition);
        }
        for (JoinExpression<?> join : getMetadata().getJoins()) {
            sources.add(join.getTarget());
        }
        indexSupport = createIndexSupport(exprToIt, ops, sources);

        if (changeToUnionQuery(condition)) {
            // TODO : handle deeper OR operations as well
            Operation<?, ?> op = (Operation<?, ?>) condition;
            
            IteratorChain<Object[]> chain = new IteratorChain<Object[]>();
            EBoolean e1 = (EBoolean) op.getArg(0), e2 = (EBoolean) op.getArg(1);
            chain.addIterator(createMultiIterator(sources, e1));
            chain.addIterator(createMultiIterator(sources, e2.and(e1.not())));
            return chain;
        } else {
            return createMultiIterator(sources, condition);
        }
    }

    protected Iterator<?> handleFromWhereSingleSource(List<Expr<?>> sources) throws Exception {
        EBoolean condition = getMetadata().getWhere();
        JoinExpression<?> join = getMetadata().getJoins().get(0);
        sources.add(join.getTarget());
        indexSupport = createIndexSupport(exprToIt, ops, sources);
        // create a simple projecting iterator for Object -> Object[]

        if (changeToUnionQuery(condition)) {
            Operation<?, ?> op = (Operation<?, ?>) condition;
            
            IteratorChain<Object[]> chain = new IteratorChain<Object[]>();
            EBoolean e1 = (EBoolean) op.getArg(0), e2 = (EBoolean) op.getArg(1);
            Iterator<?> it1 = indexSupport.getChildFor(e1).getIterator( join.getTarget());
            chain.addIterator(multiArgFilter(ops, toArrayIterator(it1), sources, e1));
            Iterator<?> it2 = indexSupport.getChildFor(e2.and(e1.not())).getIterator(join.getTarget());
            chain.addIterator(multiArgFilter(ops, toArrayIterator(it2), sources, e2.and(e1.not())));
            return chain;
        } else {
            Iterator<?> it = toArrayIterator(indexSupport.getChildFor(condition).getIterator(join.getTarget()));
            if (condition != null) {
                // wrap the iterator if a where constraint is available
                it = multiArgFilter(ops, it, sources, condition);
            }
            return it;
        }

    }

    @SuppressWarnings("unchecked")
    protected Iterator<?> handleOrderBy(List<Expr<?>> sources, Iterator<?> it) throws Exception {
        // create a projection for the order
        List<OrderSpecifier<?>> orderBy = getMetadata().getOrderBy();
        Expr<Object>[] orderByExpr = new Expr[orderBy.size()];
        boolean[] directions = new boolean[orderBy.size()];
        for (int i = 0; i < orderBy.size(); i++) {
            orderByExpr[i] = (Expr) orderBy.get(i).target;
            directions[i] = orderBy.get(i).order == Order.ASC;
        }
        Expr<?> expr = new Expr.EArrayConstructor<Object>(Object.class, orderByExpr);
        Evaluator ev = EvaluatorUtils.create(ops, sources, expr);

        // transform the iterator to list
        List<Object[]> itAsList = IteratorUtils.toList((Iterator<Object[]>) it);
        Collections.sort(itAsList, new MultiComparator(ev, directions));
        it = itAsList.iterator();
        return it;
    }

    protected <RT> Iterator<RT> handleSelect(Iterator<?> it, List<Expr<?>> sources, Expr<RT> projection) throws Exception {
        return transform(ops, it, sources, projection);
    }
    
    @SuppressWarnings("unchecked")
    public CloseableIterator<Object[]> iterate(Expr<?> first, Expr<?> second, Expr<?>... rest) {
        Expr<?>[] full = asArray(new Expr[rest.length + 2], first, second, rest);
        Expr<Object[]> projection = new Expr.EArrayConstructor(Object.class, full);
        
        addToProjection(projection);
        try {
            return wrap(createIterator(projection));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public <RT> CloseableIterator<RT> iterate(Expr<RT> projection) {        
        addToProjection(projection);
        try {
            return wrap(createIterator(projection));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public List<Object[]> list(Expr<?> e1, Expr<?> e2, Expr<?>... rest) {
        try{
            return super.list(e1, e2, rest);
        }finally{
            close();
        }
    }

    public <RT> List<RT> list(Expr<RT> projection) {
        try{
            return super.list(projection);
        }finally{
            close();
        }
    }

    public <RT> List<RT> list(RT projection) {
        return list(MiniApi.getAny(projection));
    }
    
    public void setSequentialUnion(boolean sequentialUnion) {
        this.sequentialUnion = sequentialUnion;
    }

    public void setSortSources(boolean s) {
        this.sortSources = s;
    }
    
    public void setSourceSortingSupport(SourceSortingSupport sourceSortingSupport) {
        this.sourceSortingSupport = sourceSortingSupport;
    }

    public void setWrapIterators(boolean w) {
        this.wrapIterators = w;
    }

    public <RT> RT uniqueResult(Expr<RT> expr) {
        try {
            return super.uniqueResult(expr);
        } finally {
            close();
        }
    }

    private <T> CloseableIterator<T> wrap(final Iterator<T> it) {
        return new CloseableIterator<T>() {
            public void close() throws IOException {
                AbstractColQuery.this.close();
            }
            public boolean hasNext() {
                return it.hasNext();
            }

            public T next() {
                return it.next();
            }
            public void remove() {
                it.remove();
            }
        };
    }


}
