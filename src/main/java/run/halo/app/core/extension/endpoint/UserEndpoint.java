package run.halo.app.core.extension.endpoint;

import static java.util.Comparator.comparing;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static run.halo.app.extension.ListResult.generateGenericClass;
import static run.halo.app.extension.router.QueryParamBuildUtil.buildParametersFromType;
import static run.halo.app.extension.router.selector.SelectorUtil.labelAndFieldSelectorToPredicate;

import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.common.util.StringUtils;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Role;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.service.UserService;
import run.halo.app.extension.Comparators;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.exception.ExtensionNotFoundException;
import run.halo.app.extension.router.IListRequest;
import run.halo.app.infra.exception.UserNotFoundException;
import run.halo.app.infra.utils.JsonUtils;

@Component
public class UserEndpoint implements CustomEndpoint {

    private static final String SELF_USER = "-";
    private final ReactiveExtensionClient client;
    private final UserService userService;

    public UserEndpoint(ReactiveExtensionClient client, UserService userService) {
        this.client = client;
        this.userService = userService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "api.console.halo.run/v1alpha1/User";
        return SpringdocRouteBuilder.route()
            .GET("/users/-", this::me, builder -> builder.operationId("GetCurrentUserDetail")
                .description("Get current user detail")
                .tag(tag)
                .response(responseBuilder().implementation(User.class)))
            .PUT("/users/-", this::updateProfile,
                builder -> builder.operationId("UpdateCurrentUser")
                    .description("Update current user profile, but password.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder().required(true).implementation(User.class))
                    .response(responseBuilder().implementation(User.class)))
            .POST("/users/{name}/permissions", this::grantPermission,
                builder -> builder.operationId("GrantPermission")
                    .description("Grant permissions to user")
                    .tag(tag)
                    .parameter(parameterBuilder().in(ParameterIn.PATH).name("name")
                        .description("User name")
                        .required(true))
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .implementation(GrantRequest.class))
                    .response(responseBuilder().implementation(User.class)))
            .GET("/users/{name}/permissions", this::getUserPermission,
                builder -> builder.operationId("GetPermissions")
                    .description("Get permissions of user")
                    .tag(tag)
                    .parameter(parameterBuilder().in(ParameterIn.PATH).name("name")
                        .description("User name")
                        .required(true))
                    .response(responseBuilder().implementation(UserPermission.class)))
            .PUT("/users/{name}/password", this::changePassword,
                builder -> builder.operationId("ChangePassword")
                    .description("Change password of user.")
                    .tag(tag)
                    .parameter(parameterBuilder().in(ParameterIn.PATH).name("name")
                        .description(
                            "Name of user. If the name is equal to '-', it will change the "
                                + "password of current user.")
                        .required(true))
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .implementation(ChangePasswordRequest.class))
                    .response(responseBuilder()
                        .implementation(User.class))
            )
            .GET("users", this::list, builder -> {
                builder.operationId("ListUsers")
                    .tag(tag)
                    .description("List users")
                    .response(responseBuilder().implementation(generateGenericClass(User.class)));
                buildParametersFromType(builder, ListRequest.class);
            })
            .build();
    }

    private Mono<ServerResponse> updateProfile(ServerRequest request) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Authentication::getName)
            .flatMap(currentUserName -> client.get(User.class, currentUserName))
            .flatMap(currentUser -> request.bodyToMono(User.class)
                .filter(user ->
                    Objects.equals(user.getMetadata().getName(),
                        currentUser.getMetadata().getName()))
                .switchIfEmpty(
                    Mono.error(() -> new ServerWebInputException("Username didn't match.")))
                .map(user -> {
                    var spec = currentUser.getSpec();
                    var newSpec = user.getSpec();
                    spec.setAvatar(newSpec.getAvatar());
                    spec.setBio(newSpec.getBio());
                    spec.setDisplayName(newSpec.getDisplayName());
                    spec.setTwoFactorAuthEnabled(newSpec.getTwoFactorAuthEnabled());
                    spec.setEmail(newSpec.getEmail());
                    spec.setPhone(newSpec.getPhone());
                    return currentUser;
                }))
            .flatMap(client::update)
            .flatMap(updatedUser -> ServerResponse.ok().bodyValue(updatedUser));
    }

    Mono<ServerResponse> changePassword(ServerRequest request) {
        final var nameInPath = request.pathVariable("name");
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> SELF_USER.equals(nameInPath) ? ctx.getAuthentication().getName()
                : nameInPath)
            .flatMap(username -> request.bodyToMono(ChangePasswordRequest.class)
                .switchIfEmpty(Mono.defer(() ->
                    Mono.error(new ServerWebInputException("Request body is empty"))))
                .flatMap(changePasswordRequest -> {
                    var password = changePasswordRequest.password();
                    // encode password
                    return userService.updateWithRawPassword(username, password);
                }))
            .flatMap(updatedUser -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updatedUser));
    }

    record ChangePasswordRequest(
        @Schema(description = "New password.", required = true, minLength = 6)
        String password) {
    }

    @NonNull
    Mono<ServerResponse> me(ServerRequest request) {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(ctx -> {
                var name = ctx.getAuthentication().getName();
                return client.get(User.class, name)
                    .onErrorMap(ExtensionNotFoundException.class,
                        e -> new UserNotFoundException(name));
            })
            .flatMap(user -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(user));
    }

    @NonNull
    Mono<ServerResponse> grantPermission(ServerRequest request) {
        var username = request.pathVariable("name");
        return request.bodyToMono(GrantRequest.class)
            .switchIfEmpty(
                Mono.error(() -> new ServerWebInputException("Request body is empty")))
            .flatMap(grantRequest -> userService.grantRoles(username, grantRequest.roles())
                .then(ServerResponse.ok().build()));
    }

    private Mono<GrantRequest> checkRoles(GrantRequest request) {
        return Flux.fromIterable(request.roles)
            .flatMap(role -> client.get(Role.class, role))
            .then(Mono.just(request));
    }

    record GrantRequest(Set<String> roles) {
    }

    @NonNull
    private Mono<ServerResponse> getUserPermission(ServerRequest request) {
        String name = request.pathVariable("name");
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> SELF_USER.equals(name) ? ctx.getAuthentication().getName() : name)
            .flatMapMany(userService::listRoles)
            .reduce(new LinkedHashSet<Role>(), (list, role) -> {
                list.add(role);
                return list;
            })
            .map(roles -> {
                Set<String> uiPermissions = roles.stream()
                    .map(role -> role.getMetadata().getAnnotations())
                    .filter(Objects::nonNull)
                    .map(this::mergeUiPermissions)
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
                return new UserPermission(roles, uiPermissions);
            })
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(result)
            );
    }

    private Set<String> mergeUiPermissions(Map<String, String> annotations) {
        Set<String> result = new LinkedHashSet<>();
        String permissionsStr = annotations.get(Role.UI_PERMISSIONS_AGGREGATED_ANNO);
        if (StringUtils.isNotBlank(permissionsStr)) {
            result.addAll(JsonUtils.jsonToObject(permissionsStr,
                new TypeReference<LinkedHashSet<String>>() {
                }));
        }
        String uiPermissionStr = annotations.get(Role.UI_PERMISSIONS_ANNO);
        if (StringUtils.isNotBlank(uiPermissionStr)) {
            result.addAll(JsonUtils.jsonToObject(uiPermissionStr,
                new TypeReference<LinkedHashSet<String>>() {
                }));
        }
        return result;
    }

    record UserPermission(@Schema(required = true) Set<Role> roles,
                          @Schema(required = true) Set<String> uiPermissions) {
    }

    public class ListRequest extends IListRequest.QueryListRequest {

        private final ServerWebExchange exchange;

        public ListRequest(ServerRequest request) {
            super(request.queryParams());
            this.exchange = request.exchange();
        }

        @Schema(name = "keyword")
        public String getKeyword() {
            return queryParams.getFirst("keyword");
        }

        @Schema(name = "role")
        public String getRole() {
            return queryParams.getFirst("role");
        }

        @ArraySchema(uniqueItems = true,
            arraySchema = @Schema(name = "sort",
                description = "Sort property and direction of the list result. Supported fields: "
                    + "creationTimestamp"),
            schema = @Schema(description = "like field,asc or field,desc",
                implementation = String.class,
                example = "creationTimestamp,desc"))
        public Sort getSort() {
            return SortResolver.defaultInstance.resolve(exchange);
        }

        public Predicate<User> toPredicate() {
            Predicate<User> displayNamePredicate = user -> {
                var keyword = getKeyword();
                if (!org.springframework.util.StringUtils.hasText(keyword)) {
                    return true;
                }
                var displayName = user.getSpec().getDisplayName();
                if (!org.springframework.util.StringUtils.hasText(displayName)) {
                    return false;
                }
                return displayName.toLowerCase().contains(keyword.trim().toLowerCase());
            };
            Predicate<User> rolePredicate = user -> {
                var role = getRole();
                if (role == null) {
                    return true;
                }
                var annotations = user.getMetadata().getAnnotations();
                if (annotations == null || !annotations.containsKey(User.ROLE_NAMES_ANNO)) {
                    return false;
                } else {
                    Pattern pattern = Pattern.compile("\\[\"([^\"]*)\"\\]");
                    Matcher matcher = pattern.matcher(annotations.get(User.ROLE_NAMES_ANNO));
                    if (matcher.find()) {
                        return matcher.group(1).equals(role);
                    } else {
                        return false;
                    }
                }
            };
            return displayNamePredicate
                .and(rolePredicate)
                .and(labelAndFieldSelectorToPredicate(getLabelSelector(), getFieldSelector()));
        }

        public Comparator<User> toComparator() {
            var sort = getSort();
            var ctOrder = sort.getOrderFor("creationTimestamp");
            List<Comparator<User>> comparators = new ArrayList<>();
            if (ctOrder != null) {
                Comparator<User> comparator =
                    comparing(user -> user.getMetadata().getCreationTimestamp());
                if (ctOrder.isDescending()) {
                    comparator = comparator.reversed();
                }
                comparators.add(comparator);
            }
            comparators.add(Comparators.compareCreationTimestamp(false));
            comparators.add(Comparators.compareName(true));
            return comparators.stream()
                .reduce(Comparator::thenComparing)
                .orElse(null);
        }
    }

    Mono<ServerResponse> list(ServerRequest request) {
        return Mono.just(request)
            .map(UserEndpoint.ListRequest::new)
            .flatMap(listRequest -> {
                var predicate = listRequest.toPredicate();
                var comparator = listRequest.toComparator();
                return client.list(User.class,
                    predicate,
                    comparator,
                    listRequest.getPage(),
                    listRequest.getSize());
            })
            .flatMap(listResult -> ServerResponse.ok().bodyValue(listResult));
    }
}
