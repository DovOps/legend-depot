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

package org.finos.legend.depot.server.resources.dependencies;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.finos.legend.depot.domain.VersionedData;
import org.finos.legend.depot.domain.project.ProjectVersion;
import org.finos.legend.depot.domain.project.Property;
import org.finos.legend.depot.domain.project.dependencies.ProjectDependencyWithPlatformVersions;
import org.finos.legend.depot.domain.version.VersionValidator;
import org.finos.legend.depot.server.resources.projects.ProjectsResource;
import org.finos.legend.depot.services.api.projects.ProjectsService;
import org.finos.legend.depot.core.services.tracing.resources.TracingResource;
import org.finos.legend.depot.services.api.EtagBuilder;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

import static org.finos.legend.depot.core.services.tracing.ResourceLoggingAndTracing.GET_DEPENDANT_PROJECTS;
import static org.finos.legend.depot.core.services.tracing.ResourceLoggingAndTracing.GET_PROJECT_DEPENDENCIES;
import static org.finos.legend.depot.core.services.tracing.ResourceLoggingAndTracing.GET_PROJECT_DEPENDENCY_TREE;


@Path("")
@Tag(name = "Dependencies")
public class DependenciesResource extends TracingResource
{
    private final ProjectsService projectApi;

    @Inject
    public DependenciesResource(ProjectsService projectApi)
    {

        this.projectApi = projectApi;
    }

    @GET
    @Path("/projects/{groupId}/{artifactId}/versions/{versionId}/projectDependencies")
    @Operation(summary = GET_PROJECT_DEPENDENCIES)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProjectDependencies(@PathParam("groupId") String groupId,
                                           @PathParam("artifactId") String artifactId,
                                           @PathParam("versionId") @Parameter(description = VersionValidator.VALID_VERSION_ID_TXT) String versionId,
                                           @QueryParam("transitive") @DefaultValue("false") @Parameter(description = "Whether to return transitive dependencies") boolean transitive,
                                           @Context Request request)
    {
        return handle(GET_PROJECT_DEPENDENCIES, GET_PROJECT_DEPENDENCIES + groupId + artifactId, () -> this.projectApi.getDependencies(groupId, artifactId, versionId, transitive), request, () -> EtagBuilder.create().withGAV(groupId, artifactId, versionId).build());
    }

    @POST
    @Path("/projects/analyzeDependencyTree")
    @Operation(summary = GET_PROJECT_DEPENDENCY_TREE)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyzeDependencyTree(@Parameter(description = "projectDependencies") List<ProjectVersion> projectDependencies)
    {
        return handleResponse(GET_PROJECT_DEPENDENCY_TREE, () -> this.projectApi.getProjectDependencyReport(projectDependencies));
    }

    @GET
    @Path("/projects/{groupId}/{artifactId}/versions/{versionId}/dependantProjects")
    @Operation(summary = GET_DEPENDANT_PROJECTS)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDependantProjects(@PathParam("groupId") String groupId,
                                         @PathParam("artifactId") String artifactId,
                                         @PathParam("versionId") @Parameter(description = VersionValidator.VALID_VERSION_ID_TXT) String versionId,
                                         @QueryParam("latestOnly") @DefaultValue("false")
                                                                       @Parameter(description = "Whether to only return the latest version of dependant projects") boolean latestOnly
    )
    {
        return handleResponse(GET_DEPENDANT_PROJECTS, GET_DEPENDANT_PROJECTS + groupId + artifactId, () -> transform(this.projectApi.getDependantProjects(groupId, artifactId, versionId, latestOnly)));
    }



    @JsonIgnoreProperties(ignoreUnknown = true)
    @Deprecated
    private static final class ProjectVersionPlatformDependency extends VersionedData
    {
        @JsonProperty
        private List<ProjectsResource.ProjectVersionProperty> platformsVersion;
        @JsonProperty
        private ProjectVersion dependency;

        public ProjectVersionPlatformDependency(String groupId, String artifactId, String versionId, ProjectVersion dependency, List<ProjectsResource.ProjectVersionProperty> platformsVersion)
        {
            super(groupId, artifactId, versionId);
            this.platformsVersion = platformsVersion;
            this.dependency = dependency;
        }

        public List<ProjectsResource.ProjectVersionProperty> getPlatformsVersion()
        {
            return platformsVersion;
        }

        public ProjectVersion getDependency()
        {
            return dependency;
        }

        @Override
        public boolean equals(Object obj)
        {
            return EqualsBuilder.reflectionEquals(this, obj);
        }

        @Override
        public int hashCode()
        {
            return HashCodeBuilder.reflectionHashCode(this);
        }

    }

    private List<ProjectVersionPlatformDependency> transform(List<ProjectDependencyWithPlatformVersions> dependentProjects)
    {
        return dependentProjects.stream().map(dep -> new ProjectVersionPlatformDependency(dep.getGroupId(),dep.getArtifactId(),dep.getVersionId(),dep.getDependency(),transformPropertyToProjectProperty(dep.getPlatformsVersion(),dep.getVersionId()))).collect(Collectors.toList());
    }

    private List<ProjectsResource.ProjectVersionProperty> transformPropertyToProjectProperty(List<Property> properties, String versionId)
    {
        return properties.stream().map(p -> new ProjectsResource.ProjectVersionProperty(p.getPropertyName(), p.getValue(), versionId)).collect(Collectors.toList());
    }

}
