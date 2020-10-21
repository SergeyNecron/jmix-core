/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.jmix.core.impl.serialization;

import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.jmix.core.*;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.datatype.DatatypeRegistry;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.core.metamodel.model.Range;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.*;

@Component("core_EntitySerialization")
public class EntitySerializationImpl implements EntitySerialization {

    private static final Logger log = LoggerFactory.getLogger(EntitySerializationImpl.class);

    @Autowired
    protected MetadataTools metadataTools;

    @Autowired
    protected Metadata metadata;

    @Autowired
    protected EntityStates entityStates;

    @Autowired
    protected DatatypeRegistry datatypeRegistry;

    @Autowired
    protected EntitySerializationTokenManager tokenManager;

    @Autowired
    protected CoreProperties coreProperties;

    protected ThreadLocal<EntitySerializationContext> context =
            ThreadLocal.withInitial(EntitySerializationContext::new);

    /**
     * Class is used for storing a collection of entities already processed during the serialization.
     */
    protected static class EntitySerializationContext {
        protected Table<Object, MetaClass, Object> processedEntities = HashBasedTable.create();

        protected Table<Object, MetaClass, Object> getProcessedEntities() {
            return processedEntities;
        }
    }

    @Override
    public String toJson(Object entity) {
        return toJson(entity, null);
    }

    @Override
    public String toJson(Object entity,
                         @Nullable FetchPlan fetchPlan,
                         EntitySerializationOption... options) {
        context.remove();
        return createGsonForSerialization(fetchPlan, options).toJson(entity);
    }

    @Override
    public String toJson(Collection<?> entities) {
        return toJson(entities, null);
    }

    @Override
    public String toJson(Collection<?> entities,
                         @Nullable FetchPlan fetchPlan,
                         EntitySerializationOption... options) {
        context.remove();
        return createGsonForSerialization(fetchPlan, options).toJson(entities);
    }

    @Override
    public String objectToJson(Object object, EntitySerializationOption... options) {
        context.remove();
        return createGsonForSerialization(null, options).toJson(object);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T entityFromJson(String json,
                                @Nullable MetaClass metaClass,
                                EntitySerializationOption... options) {
        context.remove();
        return (T) createGsonForDeserialization(metaClass, options).fromJson(json, Entity.class);
    }

    @Override
    public <T> Collection<T> entitiesCollectionFromJson(String json,
                                                        @Nullable MetaClass metaClass,
                                                        EntitySerializationOption... options) {
        context.remove();
        Type collectionType = new TypeToken<Collection<Entity>>() {
        }.getType();
        return createGsonForDeserialization(metaClass, options).fromJson(json, collectionType);
    }

    @Override
    public <T> T objectFromJson(String json, Type type, EntitySerializationOption... options) {
        context.remove();
        return createGsonForDeserialization(null, options).fromJson(json, type);
    }

    protected Gson createGsonForSerialization(@Nullable FetchPlan fetchPlan, EntitySerializationOption... options) {
        GsonBuilder gsonBuilder = new GsonBuilder();
        if (ArrayUtils.contains(options, EntitySerializationOption.PRETTY_PRINT)) {
            gsonBuilder.setPrettyPrinting();
        }
        gsonBuilder
                .registerTypeHierarchyAdapter(Entity.class, new EntitySerializer(fetchPlan, options))
                .registerTypeHierarchyAdapter(Date.class, new DateSerializer())
                .create();
        if (ArrayUtils.contains(options, EntitySerializationOption.SERIALIZE_NULLS)) {
            gsonBuilder.serializeNulls();
        }
        return gsonBuilder.create();
    }

    protected Gson createGsonForDeserialization(@Nullable MetaClass metaClass, EntitySerializationOption... options) {
        return new GsonBuilder()
                .registerTypeHierarchyAdapter(Entity.class, new EntityDeserializer(metaClass, options))
                .registerTypeHierarchyAdapter(Date.class, new DateDeserializer())
                .create();
    }

    @Nullable
    protected Field getField(@Nullable Class clazz, String fieldName) {
        try {
            if (clazz != null) {
                return clazz.getDeclaredField(fieldName);
            }
        } catch (NoSuchFieldException ex) {
            return getField(clazz.getSuperclass(), fieldName);
        }
        return null;
    }

    protected void makeFieldAccessible(Field field) {
        if (field != null && !Modifier.isPublic(field.getModifiers())) {
            field.setAccessible(true);
        }
    }

    protected class EntitySerializer implements JsonSerializer<Entity> {

        protected boolean compactRepeatedEntities = false;
        protected boolean serializeInstanceName;
        protected boolean doNotSerializeReadOnlyProperties = false;
        protected FetchPlan fetchPlan;

        public EntitySerializer(@Nullable FetchPlan fetchPlan, EntitySerializationOption... options) {
            this.fetchPlan = fetchPlan;
            if (options != null) {
                for (EntitySerializationOption option : options) {
                    if (option == EntitySerializationOption.COMPACT_REPEATED_ENTITIES)
                        compactRepeatedEntities = true;
                    if (option == EntitySerializationOption.SERIALIZE_INSTANCE_NAME)
                        serializeInstanceName = true;
                    if (option == EntitySerializationOption.DO_NOT_SERIALIZE_RO_NON_PERSISTENT_PROPERTIES)
                        doNotSerializeReadOnlyProperties = true;
                }
            }
        }

        @Override
        public JsonElement serialize(Entity entity, Type typeOfSrc, JsonSerializationContext context) {
            return serializeEntity(entity, fetchPlan, new HashSet<>());
        }

        protected JsonObject serializeEntity(Entity entity, @Nullable FetchPlan fetchPlan, Set<Entity> cyclicReferences) {
            JsonObject jsonObject = new JsonObject();
            MetaClass metaClass = metadata.getClass(entity.getClass());
            if (!metadataTools.isEmbeddable(metaClass)) {
                jsonObject.addProperty(ENTITY_NAME_PROP, metaClass.getName());
                if (serializeInstanceName) {
                    String instanceName = null;
                    try {
                        instanceName = metadataTools.getInstanceName(entity);
                    } catch (Exception ignored) {
                        // todo trace logging
                    }
                    jsonObject.addProperty(INSTANCE_NAME_PROP, instanceName);
                }
                writeIdField(entity, jsonObject);
                if (compactRepeatedEntities) {
                    Table<Object, MetaClass, Object> processedObjects = context.get().getProcessedEntities();
                    if (processedObjects.get(EntityValues.getId(entity), metaClass) == null) {
                        processedObjects.put(EntityValues.getId(entity), metaClass, entity);
                        writeFields(entity, jsonObject, fetchPlan, cyclicReferences);
                    }
                } else {
                    if (!cyclicReferences.contains(entity)) {
                        cyclicReferences.add(entity);
                        writeFields(entity, jsonObject, fetchPlan, cyclicReferences);
                    }
                }
            } else {
                writeFields(entity, jsonObject, fetchPlan, cyclicReferences);
            }

            if (coreProperties.isEntitySerializationSecurityTokenRequired()) {
                String securityToken = tokenManager.generateSecurityToken(entity);
                if (securityToken != null) {
                    jsonObject.addProperty("__securityToken", securityToken);
                }
            }

            return jsonObject;
        }

        protected void writeIdField(Entity entity, JsonObject jsonObject) {
            MetaClass metaClass = metadata.getClass(entity.getClass());
            MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(metaClass);
            if (primaryKeyProperty == null) {
                primaryKeyProperty = metaClass.getProperty("id");
            }
            if (primaryKeyProperty == null)
                throw new EntitySerializationException("Primary key property not found for entity " + metaClass);
            if (metadataTools.hasCompositePrimaryKey(metaClass)) {
                JsonObject serializedIdEntity = serializeEntity((Entity) EntityValues.getId(entity), null, Collections.emptySet());
                jsonObject.add("id", serializedIdEntity);
            } else {
                Datatype idDatatype = datatypeRegistry.get(primaryKeyProperty.getJavaType());
                jsonObject.addProperty("id", idDatatype.format(EntityValues.getId(entity)));
            }
        }

        protected boolean propertyWritingAllowed(MetaProperty metaProperty, Entity entity) {
            return !"id" .equals(metaProperty.getName()) &&
                    //todo dynamic attribute
                    (
//                    DynamicAttributesUtils.isDynamicAttribute(metaProperty) ||
//                            (entity instanceof BaseGenericIdEntity) ||
                            (!metadataTools.isPersistent(metaProperty) &&
                                    (metaProperty.isReadOnly() && !doNotSerializeReadOnlyProperties || !metaProperty.isReadOnly())) ||
                                    (metadataTools.isPersistent(metaProperty) && entityStates.isLoaded(entity, metaProperty.getName())));
        }

        protected void writeFields(Entity entity, JsonObject jsonObject, @Nullable FetchPlan fetchPlan, Set<Entity> cyclicReferences) {
            MetaClass metaClass = metadata.getClass(entity);
            Collection<MetaProperty> properties = new ArrayList<>(metaClass.getProperties());
//            if (entity instanceof BaseGenericIdEntity && ((BaseGenericIdEntity) entity).getDynamicAttributes() != null) {
//                List<MetaProperty> dynamicProperties = dynamicAttributes.getAttributesForMetaClass(metaClass).stream()
//                        .map(categoryAttribute -> DynamicAttributesUtils.getMetaPropertyPath(metaClass, categoryAttribute).getMetaProperty())
//                        .collect(Collectors.toList());
//                properties.addAll(dynamicProperties);
//            }
            for (MetaProperty metaProperty : properties) {
                if (propertyWritingAllowed(metaProperty, entity)) {
                    FetchPlanProperty fetchPlanProperty = null;
                    //todo dynamic attribute
//                    if (!DynamicAttributesUtils.isDynamicAttribute(metaProperty)) {
                    if (fetchPlan != null) {
                        fetchPlanProperty = fetchPlan.getProperty(metaProperty.getName());
                        if (fetchPlanProperty == null) continue;
                    }

                    if (!entityStates.isNew(entity)
                            && !entityStates.isLoaded(entity, metaProperty.getName())) {
                        continue;
                    }
//                    }

                    Object fieldValue = EntityValues.getValue(entity, metaProperty.getName());

                    //always write nulls here. GSON will not serialize them to the result if
                    //EntitySerializationOptions.SERIALIZE_NULLS was not set.
                    if (fieldValue == null) {
                        jsonObject.add(metaProperty.getName(), null);
                        continue;
                    }

                    Range propertyRange = metaProperty.getRange();
                    if (propertyRange.isDatatype()) {
                        //todo dynamic attribute
//                        if (isCollectionDynamicAttribute(metaProperty) && fieldValue instanceof Collection) {
//                            jsonObject.add(metaProperty.getName(),
//                                    serializeSimpleCollection((Collection) fieldValue, metaProperty));
//                        } else {
                        writeSimpleProperty(jsonObject, fieldValue, metaProperty);
//                        }
                    } else if (propertyRange.isEnum()) {
                        jsonObject.addProperty(metaProperty.getName(), fieldValue.toString());
                    } else if (propertyRange.isClass()) {
                        if (fieldValue instanceof Entity) {
                            JsonObject propertyJsonObject = serializeEntity((Entity) fieldValue,
                                    fetchPlanProperty != null ? fetchPlanProperty.getFetchPlan() : null,
                                    new HashSet<>(cyclicReferences));
                            jsonObject.add(metaProperty.getName(), propertyJsonObject);
                        } else if (fieldValue instanceof Collection) {
                            JsonArray jsonArray = serializeCollection((Collection) fieldValue,
                                    fetchPlanProperty != null ? fetchPlanProperty.getFetchPlan() : null,
                                    new HashSet<>(cyclicReferences));
                            jsonObject.add(metaProperty.getName(), jsonArray);
                        }
                    }
                }
            }
        }

        protected void writeSimpleProperty(JsonObject jsonObject, @NotNull Object fieldValue, MetaProperty property) {
            String propertyName = property.getName();
            if (fieldValue instanceof Number) {
                jsonObject.addProperty(propertyName, (Number) fieldValue);
            } else if (fieldValue instanceof Boolean) {
                jsonObject.addProperty(propertyName, (Boolean) fieldValue);
            } else {
                Datatype datatype = property.getRange().asDatatype();
                jsonObject.addProperty(propertyName, datatype.format(fieldValue));
            }
        }

        protected JsonArray serializeCollection(Collection value, @Nullable FetchPlan fetchPlan, Set<Entity> cyclicReferences) {
            JsonArray jsonArray = new JsonArray();
            value.stream()
                    .filter(e -> e instanceof Entity)
                    .forEach(e -> {
                        JsonObject jsonObject = serializeEntity((Entity) e, fetchPlan, new HashSet<>(cyclicReferences));
                        jsonArray.add(jsonObject);
                    });
            return jsonArray;
        }

        protected JsonArray serializeSimpleCollection(Collection fieldValue, MetaProperty property) {
            JsonArray jsonArray = new JsonArray();
            fieldValue.stream()
                    .forEach(item -> {
                        if (item instanceof Number) {
                            jsonArray.add((Number) item);
                        } else if (item instanceof Boolean) {
                            jsonArray.add((Boolean) item);
                        } else {
                            Datatype datatype = property.getRange().asDatatype();
                            jsonArray.add(datatype.format(item));
                        }
                    });
            return jsonArray;
        }
    }

    protected class EntityDeserializer implements JsonDeserializer<Entity> {

        protected MetaClass metaClass;

        public EntityDeserializer(@Nullable MetaClass metaClass, EntitySerializationOption... options) {
            this.metaClass = metaClass;
        }

        @Override
        public Entity deserialize(JsonElement jsonElement, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return (Entity) readEntity(jsonElement.getAsJsonObject(), metaClass);
        }

        protected Object readEntity(JsonObject jsonObject, @Nullable MetaClass metaClass) {
            Object pkValue = null;
            MetaClass resultMetaClass = metaClass;
            JsonElement idJsonElement = jsonObject.get("id");

            JsonPrimitive entityNameJsonPrimitive = jsonObject.getAsJsonPrimitive(ENTITY_NAME_PROP);
            if (entityNameJsonPrimitive != null) {
                String entityName = entityNameJsonPrimitive.getAsString();
                resultMetaClass = metadata.getClass(entityName);
            }


            if (resultMetaClass == null) {
                throw new EntitySerializationException("Cannot deserialize an entity. MetaClass is not defined");
            }

            Object entity = metadata.create(resultMetaClass);
            clearFields(entity);

            MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(resultMetaClass);
            if (primaryKeyProperty != null) {
                if (idJsonElement != null) {
                    if (metadataTools.hasCompositePrimaryKey(resultMetaClass)) {
                        MetaClass pkMetaClass = primaryKeyProperty.getRange().asClass();
                        pkValue = readEntity(idJsonElement.getAsJsonObject(), pkMetaClass);
                    } else {
                        String idString = idJsonElement.getAsJsonPrimitive().getAsString();
                        try {
                            Datatype pkDatatype = datatypeRegistry.get(primaryKeyProperty.getJavaType());
                            pkValue = pkDatatype.parse(idString);
                        } catch (ParseException e) {
                            throw new EntitySerializationException(e);
                        }
                    }
                } else if (!"id" .equals(primaryKeyProperty.getName())) {
                    //pk may be in another field, not "id"
                    JsonElement pkElement = jsonObject.get(primaryKeyProperty.getName());
                    if (pkElement != null && pkElement.isJsonPrimitive()) {
                        try {
                            Datatype pkDatatype = datatypeRegistry.get(primaryKeyProperty.getJavaType());
                            pkValue = pkDatatype.parse(pkElement.getAsJsonPrimitive().getAsString());
                        } catch (ParseException e) {
                            throw new EntitySerializationException(e);
                        }
                    }
                }
            }


            if (pkValue != null) {
                EntityValues.setId(entity, pkValue);
            }

            if (coreProperties.isEntitySerializationSecurityTokenRequired()) {
                JsonPrimitive securityTokenPrimitive = jsonObject.getAsJsonPrimitive("__securityToken");
                if (securityTokenPrimitive != null) {
                    tokenManager.restoreSecurityToken(entity, securityTokenPrimitive.getAsString());
                }
            }

            Table<Object, MetaClass, Object> processedEntities = context.get().getProcessedEntities();
            Object processedEntity = processedEntities.get(EntityValues.getId(entity), resultMetaClass);
            if (processedEntity != null) {
                entity = processedEntity;
            } else {
                if (EntityValues.getId(entity) != null) {
                    processedEntities.put(EntityValues.getId(entity), resultMetaClass, entity);
                }
                readFields(jsonObject, entity);
            }
            return entity;
        }

        protected boolean propertyReadRequired(String propertyName) {
            return !"id" .equals(propertyName) && !ENTITY_NAME_PROP.equals(propertyName) && !"__securityToken" .equals(propertyName);
        }

        protected void readFields(JsonObject jsonObject, Object entity) {
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                String propertyName = entry.getKey();
                if (!propertyReadRequired(propertyName)) continue;
                JsonElement propertyValue = entry.getValue();
                MetaClass metaClass = metadata.getClass(entity.getClass());
                MetaPropertyPath metaPropertyPath = metadataTools.resolveMetaPropertyPathOrNull(metaClass, propertyName);
                MetaProperty metaProperty = metaPropertyPath != null ? metaPropertyPath.getMetaProperty() : null;
                if (metaProperty != null) {
                    //todo dynamic attribute
//                    if (entity instanceof BaseGenericIdEntity
//                            && DynamicAttributesUtils.isDynamicAttribute(propertyName)
//                            && ((BaseGenericIdEntity) entity).getDynamicAttributes() == null) {
//                        fetchDynamicAttributes(entity);
//                    }

                    if (propertyValue.isJsonNull()) {
                        EntityValues.setValue(entity, propertyName, null);
//                        entity.setValue(propertyName, null);
                        continue;
                    }

                    if (metaProperty.isReadOnly()) {
                        continue;
                    }
                    Class<?> propertyType = metaProperty.getJavaType();
                    Range propertyRange = metaProperty.getRange();
                    if (propertyRange.isDatatype()) {
                        Object value;
                        //todo dynamic attribute
/*                        if (isCollectionDynamicAttribute(metaProperty)) {
                            if (propertyValue.isJsonArray()) {
                                value = readSimpleCollection(propertyValue.getAsJsonArray(), metaProperty);
                            } else {
                                value = readSimpleProperty(propertyValue, propertyRange.asDatatype());
                            }
                        } else {*/
                        //for property with List<String> type the propertyRange.isDatatype() will be true and the property type will be a
                        //collection
                        if (Collection.class.isAssignableFrom(propertyType)) {
                            value = readSimpleCollection(propertyValue.getAsJsonArray(), metaProperty);
                        } else {
                            value = readSimpleProperty(propertyValue, propertyRange.asDatatype());
                        }
//                        }
                        EntityValues.setValue(entity, propertyName, value);
                    } else if (propertyRange.isEnum()) {
                        String stringValue = propertyValue.getAsString();
                        try {
                            Enum enumValue = Enum.valueOf((Class<Enum>) propertyType, stringValue);
                            EntityValues.setValue(entity, propertyName, enumValue);
                        } catch (Exception e) {
                            throw new EntitySerializationException(String.format("An error occurred while parsing enum. Class [%s]. Value [%s].", propertyType, stringValue));
                        }
                    } else if (propertyRange.isClass()) {
                        if (Entity.class.isAssignableFrom(propertyType)) {
                            if (metadataTools.isEmbedded(metaProperty)) {
                                EntityValues.setValue(entity, propertyName, readEmbeddedEntity(propertyValue.getAsJsonObject(), metaProperty));
                            } else {
                                //todo dynamic attribute
//                                if (isCollectionDynamicAttribute(metaProperty)) {
//                                    Collection<Entity> entities = new ArrayList<>();
//                                    for (JsonElement jsonElement : propertyValue.getAsJsonArray()) {
//                                        Entity entityForList = readEntity(jsonElement.getAsJsonObject(), metaProperty.getRange().asClass());
//                                        entities.add(entityForList);
//                                    }
//                                    EntityValues.setValue(entity, propertyName, entities);
//                                } else {
//                                    EntityValues.setValue(entity, propertyName, readEntity(propertyValue.getAsJsonObject(), propertyRange.asClass()));
//                                }
                                EntityValues.setValue(entity, propertyName, readEntity(propertyValue.getAsJsonObject(), propertyRange.asClass()));
                            }
                        } else if (Collection.class.isAssignableFrom(propertyType)) {
                            Collection entities = readCollection(propertyValue.getAsJsonArray(), metaProperty);
                            EntityValues.setValue(entity, propertyName, entities);
                        }
                    }
                } else {
                    log.warn("Entity {} doesn't contain a '{}' property", metadata.getClass(entity.getClass()).getName(), propertyName);
                }
            }

        }

        @Nullable
        protected Object readSimpleProperty(JsonElement valueElement, Datatype propertyType) {
            String value = valueElement.getAsString();
            if (value == null) return null;
            try {
                Class javaClass = propertyType.getJavaClass();
                if (BigDecimal.class.isAssignableFrom(javaClass)) {
                    return valueElement.getAsBigDecimal();
                } else if (Long.class.isAssignableFrom(javaClass)) {
                    return valueElement.getAsLong();
                } else if (Integer.class.isAssignableFrom(javaClass)) {
                    return valueElement.getAsInt();
                } else if (Double.class.isAssignableFrom(javaClass)) {
                    return valueElement.getAsDouble();
                }
                return propertyType.parse(value);
            } catch (ParseException e) {
                throw new EntitySerializationException(String.format("An error occurred while parsing property. Type [%s]. Value [%s].", propertyType, value), e);
            }
        }

        protected Object readEmbeddedEntity(JsonObject jsonObject, MetaProperty metaProperty) {
            MetaClass metaClass = metaProperty.getRange().asClass();
            Object entity = metadata.create(metaClass);
            clearFields(entity);
            readFields(jsonObject, entity);

            if (coreProperties.isEntitySerializationSecurityTokenRequired()) {
                String securityToken = tokenManager.generateSecurityToken(entity);
                if (securityToken != null) {
                    jsonObject.addProperty("__securityToken", securityToken);
                }
            }

            return entity;
        }

        protected Collection readCollection(JsonArray jsonArray, MetaProperty metaProperty) {
            Collection<Object> entities;
            Class<?> propertyType = metaProperty.getJavaType();
            if (List.class.isAssignableFrom(propertyType)) {
                entities = new ArrayList<>();
            } else if (Set.class.isAssignableFrom(propertyType)) {
                entities = new LinkedHashSet<>();
            } else {
                throw new EntitySerializationException(String.format("Could not instantiate collection with class [%s].", propertyType));
            }

            jsonArray.forEach(jsonElement -> {
                Object entityForList = readEntity(jsonElement.getAsJsonObject(), metaProperty.getRange().asClass());
                entities.add(entityForList);
            });
            return entities;
        }

        protected Collection readSimpleCollection(JsonArray jsonArray, MetaProperty metaProperty) {
            Collection collection = new ArrayList();
            jsonArray.forEach(jsonElement -> {
                Object item = readSimpleProperty(jsonElement, metaProperty.getRange().asDatatype());
                collection.add(item);
            });
            return collection;
        }

        protected void clearFields(Object entity) {
            MetaClass metaClass = metadata.getClass(entity.getClass());
            for (MetaProperty metaProperty : metaClass.getProperties()) {
                if (metaProperty.getName().equals(metadataTools.getPrimaryKeyName(metaClass)) ||
                        metaProperty.getName().equals(metadataTools.getUuidPropertyName(entity.getClass())))
                    continue;

                Field field = getField(entity.getClass(), metaProperty.getName());
                if (field != null) {
                    makeFieldAccessible(field);
                    try {
                        field.set(entity, null);
                    } catch (IllegalAccessException e) {
                        throw new EntitySerializationException("Can't get access to field " + field.getName() + " of class " + entity.getClass().getName(), e);
                    }
                }
            }
        }

        /*protected void fetchDynamicAttributes(Entity entity) {
            if (entity instanceof BaseGenericIdEntity) {
                LoadContext<BaseGenericIdEntity> loadContext = new LoadContext<>(metadata.getClass(entity));
                loadContext.setId(entity.getId()).setLoadDynamicAttributes(true);
                DataManager dataService = AppBeans.get(DataManager.NAME, DataManager.class);
                BaseGenericIdEntity reloaded = dataService.load(loadContext);
                if (reloaded != null) {
                    ((BaseGenericIdEntity) entity).setDynamicAttributes(reloaded.getDynamicAttributes());
                } else {
                    ((BaseGenericIdEntity) entity).setDynamicAttributes(new HashMap<>());
                }
            }
        }*/
    }

    protected class DateSerializer implements JsonSerializer<Date> {

        private final Datatype<Date> dateDatatype;

        public DateSerializer() {
            dateDatatype = datatypeRegistry.get(Date.class);
        }

        @Override
        public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
            String formattedDate = dateDatatype.format(src);
            return new JsonPrimitive(formattedDate);
        }
    }

    protected class DateDeserializer implements JsonDeserializer<Date> {

        private final Datatype<Date> dateDatatype;

        public DateDeserializer() {
            dateDatatype = datatypeRegistry.get(Date.class);
        }

        @Override
        public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String formattedDate = json.getAsJsonPrimitive().getAsString();
            try {
                return Strings.isNullOrEmpty(formattedDate) ? null : dateDatatype.parse(formattedDate);
            } catch (ParseException e) {
                throw new EntitySerializationException("Cannot parse date " + formattedDate);
            }
        }
    }

//    protected boolean isCollectionDynamicAttribute(MetaProperty metaProperty) {
//        if (DynamicAttributesUtils.isDynamicAttribute(metaProperty.getName())) {
//            CategoryAttribute attribute = DynamicAttributesUtils.getCategoryAttribute(metaProperty);
//            return attribute != null && BooleanUtils.isTrue(attribute.getIsCollection());
//        }
//        return false;
//    }
}
