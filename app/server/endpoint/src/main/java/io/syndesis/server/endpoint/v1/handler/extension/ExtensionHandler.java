/*
 * Copyright (C) 2016 Red Hat, Inc.
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
 */
package io.syndesis.server.endpoint.v1.handler.extension;

import javax.annotation.Nonnull;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.syndesis.common.model.Dependency;
import io.syndesis.common.model.Kind;
import io.syndesis.common.model.ListResult;
import io.syndesis.common.model.ResourceIdentifier;
import io.syndesis.common.model.Violation;
import io.syndesis.common.model.extension.Extension;
import io.syndesis.common.model.integration.Integration;
import io.syndesis.common.model.integration.IntegrationDeployment;
import io.syndesis.common.model.integration.IntegrationDeploymentState;
import io.syndesis.common.model.validation.AllValidations;
import io.syndesis.common.model.validation.NonBlockingValidations;
import io.syndesis.common.model.validation.extension.ExtensionWithDomain;
import io.syndesis.common.util.KeyGenerator;
import io.syndesis.common.util.SyndesisServerException;
import io.syndesis.extension.converter.BinaryExtensionAnalyzer;
import io.syndesis.integration.api.IntegrationResourceManager;
import io.syndesis.server.api.generator.util.IconGenerator;
import io.syndesis.server.dao.file.FileDAO;
import io.syndesis.server.dao.file.FileDataManager;
import io.syndesis.server.dao.manager.DataManager;
import io.syndesis.server.endpoint.util.FilterOptionsParser;
import io.syndesis.server.endpoint.util.PaginationFilter;
import io.syndesis.server.endpoint.util.ReflectiveFilterer;
import io.syndesis.server.endpoint.util.ReflectiveSorter;
import io.syndesis.server.endpoint.v1.SyndesisRestException;
import io.syndesis.server.endpoint.v1.handler.BaseHandler;
import io.syndesis.server.endpoint.v1.operations.Deleter;
import io.syndesis.server.endpoint.v1.operations.Getter;
import io.syndesis.server.endpoint.v1.operations.PaginationOptionsFromQueryParams;
import io.syndesis.server.endpoint.v1.operations.SortOptionsFromQueryParams;
import io.syndesis.server.endpoint.v1.util.PredicateFilter;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import okio.Source;

import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Path("/extensions")
@Tag(name = "extensions")
@Component
@ConditionalOnBean(FileDAO.class)
@SuppressWarnings("PMD.GodClass")
public class ExtensionHandler extends BaseHandler implements Getter<Extension>, Deleter<Extension> {

    private final FileDAO fileStore;
    private final ExtensionActivator extensionActivator;
    private final Validator validator;
    private final IntegrationResourceManager integrationResourceManager;
    private final FileDataManager extensionDataManager;

    public ExtensionHandler(final DataManager dataMgr,
                            final FileDAO fileStore,
                            final ExtensionActivator extensionActivator,
                            final Validator validator,
                            final IntegrationResourceManager integrationResourceManager) {
        super(dataMgr);

        this.fileStore = fileStore;
        this.extensionActivator = extensionActivator;
        this.validator = validator;
        this.integrationResourceManager = integrationResourceManager;
        this.extensionDataManager = new FileDataManager(getDataManager(), fileStore);
    }

    @Override
    public Kind resourceKind() {
        return Kind.Extension;
    }

    public Validator getValidator() {
        return validator;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @SuppressWarnings({"PMD.CyclomaticComplexity","JavaUtilDate"}) // TODO refactor
    public Extension upload(MultipartFormDataInput dataInput, @Context SecurityContext sec, @QueryParam("updatedId") String updatedId) {
        Date rightNow = new Date();
        String id = KeyGenerator.createKey();
        String fileLocation = "/extensions/" + id;

        try {
            storeFile(fileLocation, dataInput);

            Extension embeddedExtension = extractExtension(fileLocation);

            if (updatedId != null) {
                // Update
                Extension replacedExtension = getDataManager().fetch(Extension.class, updatedId);
                if (!replacedExtension.getExtensionId().equals(embeddedExtension.getExtensionId())) {
                    String message = "The uploaded extensionId (" + embeddedExtension.getExtensionId() +
                        ") does not match the existing extensionId (" + replacedExtension.getExtensionId() + ")";
                    throw new SyndesisRestException(message, message, null, Response.Status.BAD_REQUEST.getStatusCode());
                }
            } else {
                // New import
                Set<String> ids = getDataManager().fetchIdsByPropertyValue(Extension.class,
                    "extensionId", embeddedExtension.getExtensionId(),
                    "status", Extension.Status.Installed.name());

                if (!ids.isEmpty()) {
                    String message = "An extension with the same extensionId (" + embeddedExtension.getExtensionId() +
                        ") is already installed. Please update the existing extension instead of importing a new one.";
                    throw new SyndesisRestException(message, message, null, Response.Status.BAD_REQUEST.getStatusCode());
                }

            }

            String icon = embeddedExtension.getIcon();
            if (icon == null) {
                icon = IconGenerator.generate("extension", embeddedExtension.getName());
            }

            Extension extension = new Extension.Builder()
                .createFrom(embeddedExtension)
                .id(id)
                .status(Extension.Status.Draft)
                .lastUpdated(rightNow)
                .createdDate(rightNow)
                .userId(sec.getUserPrincipal().getName())
                .icon(icon)
                .build();

            return getDataManager().create(extension);
        } catch (SyndesisRestException ex) {
            try {
                delete(id);
            } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") Exception ignored) {
                // ignore
            }
            throw ex;
        } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") Exception ex) {
            try {
                delete(id);
            } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") Exception ignored) {
                // ignore
            }
            String message = "An error has occurred while trying to process the technical extension. Please, check the input file.";
            throw new SyndesisRestException(message + " " + ex.getMessage(), message, null, Response.Status.BAD_REQUEST.getStatusCode(), ex);
        }
    }

    @Override
    public void delete(String id) {
        // Not a real delete of the extension: changing the status to Deleted
        Extension extension = getDataManager().fetch(Extension.class, id);

        //Delete from verifier
        extensionActivator.deleteExtension(extension);
    }

    @POST
    @Path("/{id}/validation")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "All blocking validations pass", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    @ApiResponse(responseCode = "400", description = "Found violations in validation", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    public Set<Violation> validate(@NotNull @PathParam("id") final String extensionId) {
        Extension extension = getDataManager().fetch(Extension.class, extensionId);
        return doValidate(extension);
    }

    @POST
    @Path(value = "/validation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "All blocking validations pass", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    @ApiResponse(responseCode = "400", description = "Found violations in validation", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    public Set<Violation> validate(@NotNull final Extension extension) {
        return doValidate(extension);
    }

    @POST
    @Path(value = "/{id}/install")
    @ApiResponse(responseCode = "200", description = "Installed", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    @ApiResponse(responseCode = "400", description = "Found violations in validation", content = @Content(array = @ArraySchema(schema = @Schema(implementation = Violation.class))))
    public void install(@NotNull @PathParam("id") final String id) {
        Extension extension = getDataManager().fetch(Extension.class, id);
        doValidate(extension);

        extensionActivator.activateExtension(extension);
    }

    @GET
    @Path(value = "/{id}/integrations")
    public Set<ResourceIdentifier> integrations(@NotNull @PathParam("id") final String id) {
        Extension extension = getDataManager().fetch(Extension.class, id);
        return integrations(extension);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Parameter(name = "sort", in = ParameterIn.QUERY, schema = @Schema(type = "string"), description = "Sort the result list according to the given field value")
    @Parameter(name = "direction", in = ParameterIn.QUERY, schema = @Schema(type = "string", allowableValues = {"asc", "desc"}), description = "Sorting direction when a 'sort' field is provided. Can be 'asc' (ascending) or 'desc' (descending)")
    @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1"), description = "Page number to return")
    @Parameter(name = "per_page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20"), description = "Number of records per page")
    @Parameter(name = "query", in = ParameterIn.QUERY, schema = @Schema(type = "string"), description = "The search query to filter results on")
    public ListResult<Extension> list(@Context UriInfo uriInfo, @Parameter(required = false) @QueryParam("extensionType") Extension.Type extensionType) {
        // Defaulting to display only Installed extensions
        String query = uriInfo.getQueryParameters().getFirst("query");
        if (query == null) {
            query = "status=" + Extension.Status.Installed;
        }

        return getDataManager().fetchAll(
            Extension.class,
            new ReflectiveFilterer<>(Extension.class, FilterOptionsParser.fromString(query)),
            new PredicateFilter<>(
                extension -> extensionType == null || extension.getExtensionType().equals(extensionType)
            ),
            new ReflectiveSorter<>(Extension.class, new SortOptionsFromQueryParams(uriInfo)),
            new PaginationFilter<>(new PaginationOptionsFromQueryParams(uriInfo))
        );
    }

    @GET
    @Path("/{id}/stepIcon")
    public Response getStepIcon(@NotNull @PathParam("id") final String id) {
        Extension extension = getDataManager().fetch(Extension.class, id);

        String extensionIconVal = extension.getIcon();
        if (!extensionIconVal.startsWith("extension:")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String iconFile = extensionIconVal.substring(10);

        Optional<InputStream> extensionIcon = extensionDataManager.getExtensionIcon(extension.getExtensionId(), iconFile);
        if (!extensionIcon.isPresent()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final StreamingOutput streamingOutput = (out) -> {
            try (Sink iosink = Okio.sink(out);
                BufferedSink sink = Okio.buffer(iosink);
                InputStream iconStream = extensionIcon.get();
                Source source = Okio.source(iconStream)) {
                sink.writeAll(source);
            }
        };
        return Response.ok(streamingOutput, extensionDataManager.getExtensionIconMediaType(iconFile)).build();
    }


    // ===============================================================

    @Nonnull
    private Extension extractExtension(String location) {
        try (InputStream file = fileStore.read(location)) {
            return BinaryExtensionAnalyzer.getDefault().getExtension(file);
        } catch (IOException ex) {
            throw SyndesisServerException.
                launderThrowable("Unable to load extension from filestore location " + location, ex);
        }
    }

    private void storeFile(String location, MultipartFormDataInput dataInput) {
        // Store the artifact into the filestore
        try (InputStream file = getBinaryArtifact(dataInput)) {
            if (file == null) {
                throw new IllegalArgumentException("Can't find a valid 'file' part in the multipart request");
            }

            fileStore.write(location, file);
        } catch (IOException ex) {
            throw SyndesisServerException.launderThrowable("Unable to store the file into the filestore", ex);
        }
    }

    private static InputStream getBinaryArtifact(MultipartFormDataInput input) {
        if (input == null || input.getParts() == null || input.getParts().isEmpty()) {
            throw new IllegalArgumentException("Multipart request is empty");
        }

        try {
            if (input.getParts().size() == 1) {
                InputPart filePart = input.getParts().iterator().next();
                return filePart.getBody(InputStream.class, null);
            }

            return input.getFormDataPart("file", InputStream.class, null);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error while reading multipart request", e);
        }
    }

    private Set<Violation> doValidate(Extension extension) {
        ListResult<Extension> result = getDataManager().fetchAll(Extension.class);
        ExtensionWithDomain target = new ExtensionWithDomain(extension, result.getItems());

        final Set<ConstraintViolation<ExtensionWithDomain>> constraintViolations =
            getValidator().validate(target, Default.class, AllValidations.class);

        if (!constraintViolations.isEmpty()) {
            throw new ConstraintViolationException(constraintViolations);
        }

        Set<ConstraintViolation<ExtensionWithDomain>> warnings =
            getValidator().validate(target, NonBlockingValidations.class);
        return warnings.stream()
            .map(Violation.Builder::fromConstraintViolation)
            .collect(Collectors.toSet());
    }

    private Set<ResourceIdentifier> integrations(Extension extension) {
        final Set<String> deletedIntegrationIds = getDataManager().fetchAll(Integration.class).getItems().stream()
            .map(i -> i.getId().get())
            .collect(Collectors.toSet());
        return getDataManager().fetchAll(IntegrationDeployment.class).getItems().stream()
            .filter(integrationDeployment -> isIntegrationActiveAndUsingExtension(integrationDeployment, deletedIntegrationIds, extension))
            .map(ExtensionHandler::toIntegrationResourceIdentifier)
            .distinct()
            .collect(Collectors.toSet());
    }

    private static ResourceIdentifier toIntegrationResourceIdentifier(IntegrationDeployment integrationDeployment) {
        return new ResourceIdentifier.Builder()
            .id(integrationDeployment.getIntegrationId())
            .kind(Kind.Integration)
            .name(Optional.ofNullable(integrationDeployment.getSpec().getName()))
            .build();
    }

    private boolean isIntegrationActiveAndUsingExtension(IntegrationDeployment integrationDeployment, Set<String> deletedIntegrationIds, Extension extension) {
        if (integrationDeployment == null || extension == null || integrationDeployment.getSpec() == null) {
            return false;
        }

        final String integrationId = integrationDeployment.getIntegrationId().get();
        if (deletedIntegrationIds.contains(integrationId)) {
            return false;
        }

        if (IntegrationDeploymentState.Published != integrationDeployment.getTargetState()) {
            return false;
        }

        Collection<Dependency> dependencies = integrationResourceManager.collectDependencies(integrationDeployment.getSpec());

        return dependencies.stream()
            .filter(Dependency::isExtension)
            .anyMatch(ext -> ext.getId().equals(extension.getExtensionId()));
    }

}
