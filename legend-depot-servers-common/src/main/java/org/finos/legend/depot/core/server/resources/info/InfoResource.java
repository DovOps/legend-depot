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

package org.finos.legend.depot.core.server.resources.info;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.finos.legend.depot.core.server.ServerConfiguration;
import org.finos.legend.depot.core.server.info.InfoService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@Tag(name = "Info")
@Path("")
public class InfoResource
{
    private final InfoService infoService;
    private final ServerConfiguration configuration;

    @Inject
    public InfoResource(InfoService infoService, ServerConfiguration configuration)
    {
        this.infoService = infoService;
        this.configuration = configuration;
    }

    @GET
    @Path("/info")
    @Produces({"application/json"})
    @Operation(summary = "Provides server information")
    public InfoService.ServerInfo getServerInfo()
    {
        return this.infoService.getServerInfo();
    }


    @GET
    @Path("/config")
    @Operation(summary = "Provides server config")
    public String getServerConfig() throws JsonProcessingException
    {
        return new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).setSerializationInclusion(JsonInclude.Include.NON_EMPTY).writeValueAsString(configuration);
    }
}
