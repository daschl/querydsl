/*
 * Copyright 2011, Mysema Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mysema.query.types;

/**
 * @author tiwe
 *
 * @param <T>
 */
public class EntityPathImpl<T> extends PathImpl<T> implements EntityPath<T> {
    
    private static final long serialVersionUID = -8536197750265693168L;

    public EntityPathImpl(Class<? extends T> type, String variable) {
        super(type, PathMetadataFactory.forVariable(variable));
    }

    public EntityPathImpl(Class<? extends T> type, PathMetadata<?> metadata) {
        super(type, metadata);
    }

    public EntityPathImpl(Class<? extends T> type, Path<?> parent, String property) {
        super(type, PathMetadataFactory.forProperty(parent, property));
    }

}