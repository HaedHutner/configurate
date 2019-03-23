/*
 * Configurate
 * Copyright (C) zml and Configurate contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ninja.leaping.configurate.objectmapping;

import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeToken;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.objectmapping.serialize.TypeSerializer;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

public class SimpleObjectMapper<T> implements ObjectMapper<T> {

    private final Class<T> clazz;

    private final Constructor<T> constructor;

    private final Map<String, FieldData> cachedFields = new HashMap<>();


    /**
     * Create a new object mapper that can work with objects of the given class using the
     * {@link DefaultObjectMapperFactory}.
     *
     * @param clazz The type of object
     * @param <T> The type
     * @return An appropriate object mapper instance. May be shared with other users.
     * @throws ObjectMappingException If invalid annotated fields are presented
     */
    @SuppressWarnings("unchecked")
    public static <T> SimpleObjectMapper<T> forClass(@NonNull Class<T> clazz) throws ObjectMappingException {
        return DefaultObjectMapperFactory.getInstance().getMapper(clazz);
    }

    /**
     * Creates a new object mapper bound to the given object.
     *
     * @param obj The object
     * @param <T> The object type
     * @return An appropriate object mapper instance.
     * @throws ObjectMappingException
     */
    @SuppressWarnings("unchecked")
    public static <T> SimpleObjectMapper<T>.BoundInstance forObject(@NonNull T obj) throws ObjectMappingException {
        Preconditions.checkNotNull(obj);
        return forClass((Class<T>) obj.getClass()).bind(obj);
    }

    /**
     * Holder for field-specific information
     */
    protected static class FieldData {
        private final Field field;
        private final TypeToken<?> fieldType;
        private final String comment;

        public FieldData(Field field, String comment) throws ObjectMappingException {
            this.field = field;
            this.comment = comment;
            this.fieldType = TypeToken.of(field.getGenericType());
        }

        public void deserializeFrom(Object instance, ConfigurationNode node) throws ObjectMappingException {
            TypeSerializer<?> serial = node.getOptions().getSerializers().get(this.fieldType);
            if (serial == null) {
                throw new ObjectMappingException("No TypeSerializer found for field " + field.getName() + " of type "
                        + this.fieldType);
            }
            Object newVal = node.isVirtual() ? null : serial.deserialize(this.fieldType, node);
            try {
                if (newVal == null) {
                    Object existingVal = field.get(instance);
                    if (existingVal != null) {
                        serializeTo(instance, node);
                    }
                } else {
                    field.set(instance, newVal);
                }
            } catch (IllegalAccessException e) {
                throw new ObjectMappingException("Unable to deserialize field " + field.getName(), e);
            }
        }

        @SuppressWarnings("rawtypes")
        public void serializeTo(Object instance, ConfigurationNode node) throws ObjectMappingException {
            try {
                Object fieldVal = this.field.get(instance);
                if (fieldVal == null) {
                    node.setValue(null);
                } else {
                    TypeSerializer serial = node.getOptions().getSerializers().get(this.fieldType);
                    if (serial == null) {
                        throw new ObjectMappingException("No TypeSerializer found for field " + field.getName() + " of type " + this.fieldType);
                    }
                    serial.serialize(this.fieldType, fieldVal, node);
                }

                if (node instanceof CommentedConfigurationNode && this.comment != null && !this.comment.isEmpty()) {
                    CommentedConfigurationNode commentNode = ((CommentedConfigurationNode) node);
                    if (!commentNode.getComment().isPresent()) {
                        commentNode.setComment(this.comment);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new ObjectMappingException("Unable to serialize field " + field.getName(), e);
            }
        }
    }

    public class BoundInstance implements ObjectMapper.BoundInstance<T> {

        private final T boundInstance;

        protected BoundInstance(T boundInstance) {
            this.boundInstance = boundInstance;
        }

        public T populate(ConfigurationNode source) throws ObjectMappingException {
            for (Map.Entry<String, FieldData> ent : cachedFields.entrySet()) {
                ConfigurationNode node = source.getNode(ent.getKey());
                ent.getValue().deserializeFrom(boundInstance, node);
            }
            return boundInstance;
        }

        public void serialize(ConfigurationNode target) throws ObjectMappingException {
            for (Map.Entry<String, FieldData> ent : cachedFields.entrySet()) {
                ConfigurationNode node = target.getNode(ent.getKey());
                ent.getValue().serializeTo(boundInstance, node);
            }
        }

        public T getInstance() {
            return boundInstance;
        }
    }

    /**
     * Create a new object mapper of a given type
     *
     * @param clazz The type this object mapper will work with
     * @throws ObjectMappingException if the provided class is in someway invalid
     */
    protected SimpleObjectMapper(Class<T> clazz) throws ObjectMappingException {
        this.clazz = clazz;
        Constructor<T> constructor = null;
        try {
            constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException ignore) {
        }
        this.constructor = constructor;
        Class<? super T> collectClass = clazz;
        do {
            collectFields(cachedFields, collectClass);
        } while (!(collectClass = collectClass.getSuperclass()).equals(Object.class));
    }

    protected void collectFields(Map<String, FieldData> cachedFields, Class<? super T> clazz) throws ObjectMappingException {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Setting.class)) {
                Setting setting = field.getAnnotation(Setting.class);
                String path = setting.value();
                if (path.isEmpty()) {
                    path = field.getName();
                }

                FieldData data = new FieldData(field, setting.comment());
                field.setAccessible(true);
                if (!cachedFields.containsKey(path)) {
                    cachedFields.put(path, data);
                }
            }
        }
    }

    /**
     * Create a new instance of an object of the appropriate type. This method is not
     * responsible for any population.
     *
     * @return The new object instance
     * @throws ObjectMappingException If constructing a new instance was not possible
     */
    protected T constructObject() throws ObjectMappingException {
        if (constructor == null) {
            throw new ObjectMappingException("No zero-arg constructor is available for class " + clazz + " but is required to construct new instances!");
        }
        try {
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ObjectMappingException("Unable to create instance of target class " + clazz, e);
        }
    }

    public boolean canCreateInstances() {
        return constructor != null;
    }

    public BoundInstance bind(T instance) {
        return new BoundInstance(instance);
    }

    public BoundInstance bindToNew() throws ObjectMappingException {
        return new BoundInstance(constructObject());
    }

    public Class<T> getMappedType() {
        return this.clazz;
    }
}
