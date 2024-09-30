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

package org.finos.legend.depot.store.mongo.admin.migrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.collections.api.block.function.Function3;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.finos.legend.depot.domain.project.ProjectVersion;
import org.finos.legend.depot.store.model.projects.StoreProjectVersionData;
import org.finos.legend.depot.domain.project.dependencies.VersionDependencyReport;
import org.finos.legend.depot.store.mongo.core.BaseMongo;
import org.finos.legend.depot.store.mongo.projects.ProjectsVersionsMongo;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

@Deprecated
public final class DependenciesMigration
{
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DependenciesMigration.class);
    private final MongoDatabase mongoDatabase;
    private static final String NOT_FOUND_IN_STORE = "%s-%s-%s not found in store";
    private static final String INVALID_DEPENDENCIES = "%s-%s-%s has invalid transitive dependencies";
    private static final String EXCLUDED_DEPENDENCY = "%s-%s-%s is an excluded dependency";
    private static final String VERSIONS_COLLECTION = "versionsTemp";

    private final ConcurrentMutableMap<ProjectVersion, VersionDependencyReport> transitiveDependenciesMap = new ConcurrentHashMap<>();

    public DependenciesMigration(MongoDatabase mongoDatabase)
    {
        this.mongoDatabase = mongoDatabase;
    }

    @Deprecated
    public void calculateTransitiveDependenciesForAllProjectVersions()
    {
        MongoCollection<Document> versionsCollection = mongoDatabase.getCollection(ProjectsVersionsMongo.COLLECTION);
        mongoDatabase.getCollection(VERSIONS_COLLECTION).drop();
        MongoCollection<Document> tempVersionCollection = mongoDatabase.getCollection(VERSIONS_COLLECTION);
        List<StoreProjectVersionData> versionData = new ArrayList<>();
        versionsCollection.find().forEach((Consumer<Document>) document -> versionData.add(BaseMongo.convert(new ObjectMapper(), document, StoreProjectVersionData.class)));
        List<StoreProjectVersionData> versionsToUpdate = calculateTransitiveDependenciesForAllVersions(versionData);
        try
        {
            versionsToUpdate.forEach(pv ->
            {
                tempVersionCollection.insertOne(BaseMongo.buildDocument(pv));
                LOGGER.info("%s-%s-%s insertion completed".formatted(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()));
            });
        }
        catch (Exception e)
        {
            LOGGER.info("Error while inserting data:%s".formatted(e));
        }
    }

    @Deprecated
    public void addTransitiveDependenciesToVersionData()
    {
        MongoCollection<Document> versionsCollection = mongoDatabase.getCollection(ProjectsVersionsMongo.COLLECTION);
        MongoCollection<Document> tempVersionCollection = mongoDatabase.getCollection(VERSIONS_COLLECTION);
        List<StoreProjectVersionData> versionData = new ArrayList<>();
        tempVersionCollection.find().forEach((Consumer<Document>) document -> versionData.add(BaseMongo.convert(new ObjectMapper(), document, StoreProjectVersionData.class)));
        versionData.forEach(pv ->
        {
            LOGGER.info("Updating project version with transitive dependencies: %s-%s-%s".formatted(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()));
            versionsCollection.updateOne(getArtifactAndVersionFilter(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()), Updates.combine(
                    Updates.addEachToSet("transitiveDependenciesReport.transitiveDependencies", buildProjectVersionDocument(pv.getTransitiveDependenciesReport().getTransitiveDependencies())),
                    Updates.set("transitiveDependenciesReport.valid", pv.getTransitiveDependenciesReport().isValid())));
            LOGGER.info("Completed updating project version with transitive dependencies: %s-%s-%s".formatted(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()));
        });
    }

    private List<StoreProjectVersionData> calculateTransitiveDependenciesForAllVersions(List<StoreProjectVersionData> allProjectsVersions)
    {
        AtomicInteger i = new AtomicInteger();
        List<StoreProjectVersionData> versionWithDependencies = allProjectsVersions.stream().filter(p -> !p.getVersionData().getDependencies().isEmpty() && !p.getVersionData().isExcluded()).collect(Collectors.toList());
        List<StoreProjectVersionData> versionWithoutDependencies = allProjectsVersions.stream().filter(p -> p.getVersionData().getDependencies().isEmpty() || p.getVersionData().isExcluded()).collect(Collectors.toList());
        Map<String, StoreProjectVersionData> directDependenciesMap = allProjectsVersions.stream().filter(p -> !p.getVersionData().isExcluded()).collect(Collectors.toMap(p -> p.getGroupId() + p.getArtifactId() + p.getVersionId(), Function.identity()));
        try
        {
            LOGGER.info("Dependencies count for calculation: [%s]".formatted(versionWithDependencies.size()));

            versionWithDependencies.forEach(pv ->
            {
                ProjectVersion projectVersion = new ProjectVersion(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId());
                LOGGER.info("Finding transitive dependencies for [%s-%s-%s]".formatted(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()));
                VersionDependencyReport report = this.transitiveDependenciesMap.getIfAbsentPut(projectVersion, () -> calculateTransitiveDependencies(projectVersion, getDependenciesFromMap(directDependenciesMap)));
                pv.setTransitiveDependenciesReport(report);
                LOGGER.info("Completed finding transitive dependencies for [%s-%s-%s]".formatted(pv.getGroupId(), pv.getArtifactId(), pv.getVersionId()));
                LOGGER.info("Dependencies calculation count completed: [%s]".formatted(i.incrementAndGet()));

            });
        }
        catch (Exception e)
        {
            LOGGER.info("Error finding dependencies: %s".formatted(e.getMessage()));
            throw new IllegalStateException("Error finding transitive dependencies due to: %s".formatted(e.getMessage()));
        }
        versionWithoutDependencies.stream().filter(pv -> pv.getVersionData().isExcluded()).forEach(pv -> pv.getTransitiveDependenciesReport().setValid(false));
        List<StoreProjectVersionData> finalList = new ArrayList<>();
        finalList.addAll(versionWithDependencies);
        finalList.addAll(versionWithoutDependencies);
        return finalList;
    }

    private Function3<String,String,String,StoreProjectVersionData> getDependenciesFromMap(Map<String, StoreProjectVersionData> dependenciesMap)
    {
        return (group, artifact, versionId) -> dependenciesMap.get(group + artifact + versionId);
    }

    private VersionDependencyReport calculateTransitiveDependencies(ProjectVersion projectVersion, Function3<String, String, String, StoreProjectVersionData> dependencyProvider)
    {
        Set<ProjectVersion> dependencies = new HashSet<>();
        try
        {
            StoreProjectVersionData versionData = dependencyProvider.value(projectVersion.getGroupId(), projectVersion.getArtifactId(), projectVersion.getVersionId());
            if (versionData != null)
            {
                if (versionData.getVersionData().isExcluded())
                {
                    LOGGER.error(EXCLUDED_DEPENDENCY.formatted(projectVersion.getGroupId(), projectVersion.getArtifactId(), projectVersion.getVersionId()));
                    throw new IllegalStateException(INVALID_DEPENDENCIES.formatted(projectVersion.getGroupId(), projectVersion.getArtifactId(), projectVersion.getVersionId()));
                }
                List<ProjectVersion> artifactDependencies = versionData.getVersionData().getDependencies();
                if (!artifactDependencies.isEmpty())
                {
                    artifactDependencies.forEach(dep ->
                    {
                        ProjectVersion pv = new ProjectVersion(dep.getGroupId(), dep.getArtifactId(), dep.getVersionId());
                        VersionDependencyReport transitiveDependencies = this.transitiveDependenciesMap.getIfAbsentPut(pv, () -> calculateTransitiveDependencies(pv, dependencyProvider));
                        if (transitiveDependencies.isValid())
                        {
                            dependencies.add(pv);
                            dependencies.addAll(transitiveDependencies.getTransitiveDependencies());
                        }
                        else
                        {
                            LOGGER.error(INVALID_DEPENDENCIES.formatted(dep.getGroupId(), dep.getArtifactId(), dep.getVersionId()));
                            throw new IllegalStateException(INVALID_DEPENDENCIES.formatted(dep.getGroupId(), dep.getArtifactId(), dep.getVersionId()));
                        }
                    });
                }
            }
            else
            {
                LOGGER.error(NOT_FOUND_IN_STORE.formatted(projectVersion.getGroupId(), projectVersion.getArtifactId(), projectVersion.getVersionId()));
                throw new IllegalStateException(NOT_FOUND_IN_STORE.formatted(projectVersion.getGroupId(), projectVersion.getArtifactId(), projectVersion.getVersionId()));
            }
        }
        catch (IllegalStateException e)
        {
            return new VersionDependencyReport(new ArrayList<>(), false);
        }
        catch (Exception e)
        {
            LOGGER.info("Error finding dependencies: %s".formatted(e.getMessage()));
            throw new IllegalStateException("Error finding transitive dependencies with message: %s".formatted(e.getMessage()));
        }
        return new VersionDependencyReport(dependencies.stream().collect(Collectors.toList()), true);
    }

    private Bson getArtifactAndVersionFilter(String groupId, String artifactId, String versionId)
    {
        return Filters.and(Filters.eq(BaseMongo.VERSION_ID, versionId),
                Filters.and(Filters.eq(BaseMongo.GROUP_ID, groupId),
                        Filters.eq(BaseMongo.ARTIFACT_ID, artifactId)));
    }

    private List<Document> buildProjectVersionDocument(List<ProjectVersion> dependencies)
    {
        if (dependencies.isEmpty())
        {
            return new ArrayList<>();
        }
        return dependencies.stream().map(dep ->
        {
            try
            {
                return Document.parse(new ObjectMapper().writeValueAsString(dep));
            }
            catch (JsonProcessingException e)
            {
                LOGGER.error("Error converting project version: %s, to document class".formatted(dep.getGav()));
                return new Document();
            }
        }).collect(Collectors.toList());
    }
}
