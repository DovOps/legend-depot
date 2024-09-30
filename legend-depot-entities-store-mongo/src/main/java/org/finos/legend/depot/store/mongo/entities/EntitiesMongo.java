//  Copyright 2021 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package org.finos.legend.depot.store.mongo.entities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.finos.legend.depot.domain.CoordinateValidator;
import org.finos.legend.depot.store.model.entities.EntityDefinition;
import org.finos.legend.depot.domain.entity.EntityValidationErrors;
import org.finos.legend.depot.store.model.entities.StoredEntity;
import org.finos.legend.depot.domain.entity.DepotEntityOverview;
import org.finos.legend.depot.domain.entity.DepotEntity;
import org.finos.legend.depot.store.model.entities.StoredEntityData;
import org.finos.legend.depot.store.model.entities.StoredEntityStringData;
import org.finos.legend.depot.domain.project.ProjectVersion;
import org.finos.legend.depot.domain.version.VersionValidator;
import org.finos.legend.depot.store.api.entities.Entities;
import org.finos.legend.depot.store.api.entities.UpdateEntities;
import org.finos.legend.depot.store.mongo.core.BaseMongo;
import org.finos.legend.sdlc.domain.model.entity.Entity;
import org.finos.legend.sdlc.tools.entity.EntityPaths;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

public class EntitiesMongo<T extends StoredEntity> extends AbstractEntitiesMongo<T> implements Entities<T>, UpdateEntities<T>
{
    public static final String COLLECTION = "entities";
    public static final UpdateOptions INSERT_IF_ABSENT = new UpdateOptions().upsert(true);

    @Inject
    public EntitiesMongo(@Named("mongoDatabase") MongoDatabase databaseProvider)
    {
        super(databaseProvider,StoredEntity.class);
    }

    public EntitiesMongo(@Named("mongoDatabase") MongoDatabase databaseProvider, Class<T> documentClass)
    {
        super(databaseProvider, documentClass);
    }


    public static List<IndexModel> buildIndexes()
    {
        return Arrays.asList(BaseMongo.buildIndex("groupId-artifactId-versionId", BaseMongo.GROUP_ID, BaseMongo.ARTIFACT_ID, BaseMongo.VERSION_ID),
                BaseMongo.buildIndex("groupId-artifactId-versionId-entityAttributes-path", true, BaseMongo.GROUP_ID, BaseMongo.ARTIFACT_ID, BaseMongo.VERSION_ID, ENTITY_PATH),
                BaseMongo.buildIndex("groupId-artifactId-versionId-entityAttributes-package", BaseMongo.GROUP_ID, BaseMongo.ARTIFACT_ID, BaseMongo.VERSION_ID, ENTITY_PACKAGE),
                BaseMongo.buildIndex("entityAttributes-classifier", ENTITY_CLASSIFIER_PATH)
        );
    }

    @Override
    protected MongoCollection getCollection()
    {
        return getMongoCollection(COLLECTION);
    }

    @Override
    protected Bson getKeyFilter(T data)
    {
        return and(getArtifactAndVersionFilter(data.getGroupId(), data.getArtifactId(), data.getVersionId()),
                eq(ENTITY_PATH, data.getEntityAttributes().get(PATH)));
    }

    @Override
    protected void validateNewData(T entity)
    {
        List<String> errors = new ArrayList<>();
        if (!CoordinateValidator.isValidGroupId(entity.getGroupId()))
        {
            errors.add(EntityValidationErrors.INVALID_GROUP_ID.formatted(entity.getGroupId()));
        }
        if (!CoordinateValidator.isValidArtifactId(entity.getArtifactId()))
        {
            errors.add(EntityValidationErrors.INVALID_ARTIFACT_ID.formatted(entity.getArtifactId()));
        }
        if (!VersionValidator.isValid(entity.getVersionId()))
        {
            errors.add(EntityValidationErrors.INVALID_VERSION_ID.formatted(entity.getVersionId()));
        }
        if (!EntityPaths.isValidEntityPath(entity.getEntityAttributes().get(PATH).toString()))
        {
            errors.add(EntityValidationErrors.INVALID_ENTITY_PATH.formatted(entity.getEntityAttributes().get(PATH).toString()));
        }
        if (!errors.isEmpty())
        {
            throw new IllegalArgumentException("invalid data :" + String.join(",",errors));
        }
    }

    @Override
    public List<T> createOrUpdate(String groupId, String artifactId, String versionId, List<Entity> entityDefinitions)
    {
        List<StoredEntity> versionedEntities = new ArrayList<>();
        entityDefinitions.parallelStream().forEach(item ->
        {
            StoredEntity storedEntity = new StoredEntityStringData(groupId, artifactId, versionId);
            getCollection().updateOne(getEntityPathFilter(groupId, artifactId, versionId, item.getPath()), combineDocument((T) storedEntity, item, ENTITY_TYPE_STRING_DATA), INSERT_IF_ABSENT);
            versionedEntities.add(storedEntity);
        });
        return (List<T>) versionedEntities;
    }

    public List<T> createOrUpdate(List<T> versionedEntities)
    {
        versionedEntities.forEach(item -> createOrUpdate(item));
        return versionedEntities;
    }

    @Override
    public List<DepotEntity> findLatestClassifierEntities(String classifier)
    {
        return curateDepotEntity(super.findLatestEntitiesByClassifier(classifier));
    }

    @Override
    public List<DepotEntity> findReleasedClassifierEntities(String classifier)
    {
        return curateDepotEntity(super.findReleasedEntitiesByClassifier(classifier));
    }


    @Override
    public List<DepotEntity> findClassifierEntitiesByVersions(String classifier, List<ProjectVersion> projectVersions)
    {
        return curateDepotEntity(super.findEntitiesByClassifierAndVersions(classifier, projectVersions));
    }

    @Override
    public List<DepotEntityOverview> findLatestClassifierSummaries(String classifier)
    {
        return curateDepotEntityOverview(super.findLatestEntitiesByClassifier(classifier));
    }

    @Override
    public List<DepotEntityOverview> findReleasedClassifierSummaries(String classifier)
    {
        return curateDepotEntityOverview(super.findReleasedEntitiesByClassifier(classifier));
    }

    @Override
    public List<DepotEntityOverview> findClassifierSummariesByVersions(String classifier, List<ProjectVersion> projectVersions)
    {
        return curateDepotEntityOverview(super.findEntitiesByClassifierAndVersions(classifier, projectVersions));
    }

    @Override
    public List<DepotEntity> findReleasedClassifierEntities(String classifier, String search, Integer limit)
    {
        FindIterable findIterable = super.findReleasedEntitiesByClassifier(classifier, search);

        if (limit != null)
        {
            return curateDepotEntity(findIterable.limit(limit));
        }
        return curateDepotEntity(findIterable);
    }

    @Override
    public List<DepotEntity> findLatestClassifierEntities(String classifier, String search, Integer limit)
    {
        FindIterable findIterable = super.findLatestEntitiesByClassifier(classifier, search);

        if (limit != null)
        {
            return curateDepotEntity(findIterable.limit(limit));
        }
        return curateDepotEntity(findIterable);
    }

    @Override
    public List<DepotEntity> findClassifierEntitiesByVersions(String classifier, List<ProjectVersion> projectVersions, String search, Integer limit)
    {
        FindIterable findIterable = super.findEntitiesByClassifierAndVersions(classifier, search, projectVersions);

        if (limit != null)
        {
            return curateDepotEntity(findIterable.limit(limit));
        }
        return curateDepotEntity(findIterable);
    }

    protected List<DepotEntityOverview> curateDepotEntityOverview(FindIterable query)
    {
        List<DepotEntityOverview> result = new ArrayList<>();
        query.forEach((Consumer<Document>) doc ->
        {
            Map<String, ?> entity = null;
            if (doc.getString(ENTITY_TYPE).equals(ENTITY_TYPE_DATA))
            {
                entity = (Map<String, Object>) doc.get(ENTITY);
            }
            else if (doc.getString(ENTITY_TYPE).equals(ENTITY_TYPE_STRING_DATA))
            {
                entity = (Map<String, ?>) doc.get(ENTITY_ATTRIBUTES);
            }
            result.add(new DepotEntityOverview(doc.getString(BaseMongo.GROUP_ID), doc.getString(BaseMongo.ARTIFACT_ID), doc.getString(BaseMongo.VERSION_ID), (String) entity.get(PATH), (String) entity.get(CLASSIFIER_PATH)));
        });
        return result;
    }

    protected List<DepotEntity> curateDepotEntity(FindIterable query)
    {
        List<T> storedEntities = convert(query);
        if (!storedEntities.isEmpty())
        {
            return storedEntities.stream().map(storedEntity -> new DepotEntity(storedEntity.getGroupId(), storedEntity.getArtifactId(), storedEntity.getVersionId(), resolvedToEntityDefinition(storedEntity))).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    protected Entity resolvedToEntityDefinition(T storedEntity)
    {
        if (storedEntity instanceof StoredEntityData data)
        {
            return data.getEntity();
        }
        else if (storedEntity instanceof StoredEntityStringData data)
        {
            try
            {
                return objectMapper.readValue(data.getData(), EntityDefinition.class);
            }
            catch (JsonProcessingException e)
            {
                throw new IllegalStateException("Error: %s while fetching entity: %s-%s-%s-%s".formatted(e.getMessage(), storedEntity.getGroupId(), storedEntity.getArtifactId(), storedEntity.getVersionId(), data.getEntityAttributes().get("path")));
            }
        }
        else
        {
            throw new IllegalStateException("Unknown stored entity type");
        }
    }
}
