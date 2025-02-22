package com.redhat.cloud.notifications.db;

import com.redhat.cloud.notifications.models.Attributes;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EndpointDefault;
import com.redhat.cloud.notifications.models.EndpointTarget;
import com.redhat.cloud.notifications.models.EndpointType;
import com.redhat.cloud.notifications.models.EndpointWebhook;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.WebhookAttributes;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.PersistenceException;
import javax.ws.rs.BadRequestException;
import java.util.UUID;

@ApplicationScoped
public class EndpointResources {

    @Inject
    Mutiny.Session session;

    public Uni<Endpoint> createEndpoint(Endpoint endpoint) {
        return Uni.createFrom().item(endpoint)
                .onItem().transform(this::mapProperties)
                .onItem().transformToUni(session::persist)
                .call(session::flush)
                .replaceWith(endpoint);
    }

    public Multi<Endpoint> getEndpointsPerType(String tenant, EndpointType type, Boolean activeOnly, Query limiter) {
        // TODO Modify the parameter to take a vararg of Functions that modify the query
        // TODO Modify to take account selective joins (JOIN (..) UNION (..)) based on the type, same for getEndpoints
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook WHERE e.accountId = :accountId AND e.type = :endpointType";
        if (activeOnly != null) {
            query += " AND enabled = :enabled";
        }

        if (limiter != null) {
            query = limiter.getModifiedQuery(query);
        }

        Mutiny.Query<Endpoint> mutinyQuery = session.createQuery(query, Endpoint.class)
                .setParameter("accountId", tenant)
                .setParameter("endpointType", type);

        if (activeOnly != null) {
            mutinyQuery = mutinyQuery.setParameter("enabled", activeOnly);
        }

        if (limiter != null && limiter.getLimit() != null && limiter.getLimit().getLimit() > 0) {
            mutinyQuery = mutinyQuery.setMaxResults(limiter.getLimit().getLimit())
                    .setFirstResult(limiter.getLimit().getOffset());
        }

        return mutinyQuery.getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    public Uni<Long> getEndpointsCountPerType(String tenant, EndpointType type, Boolean activeOnly) {
        String query = "SELECT COUNT(*) FROM Endpoint WHERE accountId = :accountId AND type = :endpointType";
        if (activeOnly != null) {
            query += " AND enabled = :enabled";
        }

        Mutiny.Query<Long> mutinyQuery = session.createQuery(query, Long.class)
                .setParameter("accountId", tenant)
                .setParameter("endpointType", type);

        if (activeOnly != null) {
            mutinyQuery = mutinyQuery.setParameter("enabled", activeOnly);
        }

        return mutinyQuery.getSingleResult();
    }

    // TODO [BG Phase 2] Delete this method
    public Multi<Endpoint> getTargetEndpoints(String tenant, String bundleName, String applicationName, String eventTypeName) {
        // TODO Add UNION JOIN for different endpoint types here
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook JOIN e.targets t " +
                "WHERE e.enabled = TRUE AND t.eventType.name = :eventTypeName AND t.id.accountId = :accountId " +
                "AND t.eventType.application.name = :applicationName AND t.eventType.application.bundle.name = :bundleName";

        return session.createQuery(query, Endpoint.class)
                .setParameter("applicationName", applicationName)
                .setParameter("eventTypeName", eventTypeName)
                .setParameter("accountId", tenant)
                .setParameter("bundleName", bundleName)
                .getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    // TODO [BG Phase 2] Remove '_BG' suffix
    public Multi<Endpoint> getTargetEndpoints_BG(String tenant, String bundleName, String applicationName, String eventTypeName) {
        // TODO Add UNION JOIN for different endpoint types here
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook JOIN e.behaviorGroupActions bga JOIN bga.behaviorGroup.behaviors b " +
                "WHERE e.enabled = TRUE AND b.eventType.name = :eventTypeName AND bga.behaviorGroup.accountId = :accountId " +
                "AND b.eventType.application.name = :applicationName AND b.eventType.application.bundle.name = :bundleName";

        return session.createQuery(query, Endpoint.class)
                .setParameter("applicationName", applicationName)
                .setParameter("eventTypeName", eventTypeName)
                .setParameter("accountId", tenant)
                .setParameter("bundleName", bundleName)
                .getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    public Multi<Endpoint> getEndpoints(String tenant, Query limiter) {
        // TODO Add the ability to modify the getEndpoints to return also with JOIN to application_eventtypes_endpoints link table
        //      or should I just create a new method for it?
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook WHERE e.accountId = :accountId";

        if (limiter != null) {
            query = limiter.getModifiedQuery(query);
        }
        // TODO Add JOIN ON clause to proper table, such as webhooks and then read the results

        Mutiny.Query<Endpoint> mutinyQuery = session.createQuery(query, Endpoint.class)
                .setParameter("accountId", tenant);

        if (limiter != null && limiter.getLimit() != null && limiter.getLimit().getLimit() > 0) {
            mutinyQuery = mutinyQuery.setMaxResults(limiter.getLimit().getLimit())
                    .setFirstResult(limiter.getLimit().getOffset());
        }

        return mutinyQuery.getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    public Uni<Long> getEndpointsCount(String tenant) {
        String query = "SELECT COUNT(*) FROM Endpoint WHERE accountId = :accountId";
        return session.createQuery(query, Long.class)
                .setParameter("accountId", tenant)
                .getSingleResult();
    }

    public Uni<Endpoint> getEndpoint(String tenant, UUID id) {
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook WHERE e.accountId = :accountId AND e.id = :id";
        return session.createQuery(query, Endpoint.class)
                .setParameter("id", id)
                .setParameter("accountId", tenant)
                .getSingleResultOrNull();
    }

    public Uni<Boolean> deleteEndpoint(String tenant, UUID id) {
        String query = "DELETE FROM Endpoint WHERE accountId = :accountId AND id = :id";
        return session.createQuery(query)
                .setParameter("id", id)
                .setParameter("accountId", tenant)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
        // Actually, the endpoint targeting this should be repeatable
    }

    public Uni<Boolean> disableEndpoint(String tenant, UUID id) {
        return modifyEndpointStatus(tenant, id, false);
    }

    public Uni<Boolean> enableEndpoint(String tenant, UUID id) {
        return modifyEndpointStatus(tenant, id, true);
    }

    private Uni<Boolean> modifyEndpointStatus(String tenant, UUID id, boolean enabled) {
        String query = "UPDATE Endpoint SET enabled = :enabled WHERE accountId = :accountId AND id = :id";

        return session.createQuery(query)
                .setParameter("id", id)
                .setParameter("accountId", tenant)
                .setParameter("enabled", enabled)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    // TODO [BG Phase 2] Delete this method
    public Uni<Boolean> linkEndpoint(String tenant, UUID endpointId, UUID eventTypeId) {
        return Uni.createFrom().item(() -> {
            Endpoint endpoint = session.getReference(Endpoint.class, endpointId);
            EventType eventType = session.getReference(EventType.class, eventTypeId);
            return new EndpointTarget(tenant, endpoint, eventType);
        })
                .onItem().transformToUni(session::persist)
                .call(session::flush)
                .replaceWith(Boolean.TRUE)
                .onFailure().recoverWithItem(Boolean.FALSE);
    }

    // TODO [BG Phase 2] Delete this method
    public Uni<Boolean> unlinkEndpoint(String tenant, UUID endpointId, UUID eventTypeId) {
        String query = "DELETE FROM EndpointTarget WHERE id.accountId = :accountId AND eventType.id = :eventTypeId AND endpoint.id = :endpointId";

        return session.createQuery(query)
                .setParameter("accountId", tenant)
                .setParameter("eventTypeId", eventTypeId)
                .setParameter("endpointId", endpointId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    // TODO [BG Phase 2] Delete this method
    public Multi<Endpoint> getLinkedEndpoints(String tenant, UUID eventTypeId, Query limiter) {
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook JOIN e.targets t WHERE t.id.accountId = :accountId AND t.eventType.id = :eventTypeId";

        if (limiter != null) {
            query = limiter.getModifiedQuery(query);
        }

        Mutiny.Query<Endpoint> mutinyQuery = session.createQuery(query, Endpoint.class)
                .setParameter("accountId", tenant)
                .setParameter("eventTypeId", eventTypeId);

        if (limiter != null && limiter.getLimit() != null && limiter.getLimit().getLimit() > 0) {
            mutinyQuery = mutinyQuery.setMaxResults(limiter.getLimit().getLimit())
                    .setFirstResult(limiter.getLimit().getOffset());
        }

        return mutinyQuery.getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    // TODO [BG Phase 2] Delete this method
    public Multi<Endpoint> getDefaultEndpoints(String tenant) {
        String query = "SELECT e FROM Endpoint e LEFT JOIN FETCH e.webhook JOIN e.defaults d WHERE d.id.accountId = :accountId";

        return session.createQuery(query, Endpoint.class)
                .setParameter("accountId", tenant)
                .getResultList()
                .onItem().transformToMulti(Multi.createFrom()::iterable);
    }

    // TODO [BG Phase 2] Delete this method
    public Uni<Boolean> endpointInDefaults(String tenant, UUID endpointId) {
        String query = "SELECT COUNT(*) FROM EndpointDefault WHERE id.accountId = :accountId AND endpoint.id = :endpointId";

        return session.createQuery(query, Long.class)
                .setParameter("accountId", tenant)
                .setParameter("endpointId", endpointId)
                .getSingleResult()
                .onItem().transform(count -> count > 0);
    }

    // TODO [BG Phase 2] Delete this method
    public Uni<Boolean> addEndpointToDefaults(String tenant, UUID endpointId) {
        return Uni.createFrom().item(() -> {
            Endpoint endpoint = session.getReference(Endpoint.class, endpointId);
            return new EndpointDefault(tenant, endpoint);
        })
                .onItem().transformToUni(session::persist)
                .call(session::flush)
                .onFailure(PersistenceException.class).transform(a -> new BadRequestException("Given endpoint id can not be linked to default"))
                .replaceWith(Boolean.TRUE);
    }

    // TODO [BG Phase 2] Delete this method
    public Uni<Boolean> deleteEndpointFromDefaults(String tenant, UUID endpointId) {
        String query = "DELETE FROM EndpointDefault WHERE id.accountId = :accountId AND id.endpointId = :endpointId";

        return session.createQuery(query)
                .setParameter("accountId", tenant)
                .setParameter("endpointId", endpointId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    public Uni<Boolean> updateEndpoint(Endpoint endpoint) {
        // TODO Update could fail because the item did not exist, throw 404 in that case?
        // TODO Fix transaction so that we don't end up with half the updates applied
        String endpointQuery = "UPDATE Endpoint SET name = :name, description = :description, enabled = :enabled " +
                "WHERE accountId = :accountId AND id = :id";
        String webhookQuery = "UPDATE EndpointWebhook SET url = :url, method = :method, " +
                "disableSslVerification = :disableSslVerification, secretToken = :secretToken WHERE endpoint.id = :endpointId";

        return session.createQuery(endpointQuery)
                .setParameter("name", endpoint.getName())
                .setParameter("description", endpoint.getDescription())
                .setParameter("enabled", endpoint.isEnabled())
                .setParameter("accountId", endpoint.getAccountId())
                .setParameter("id", endpoint.getId())
                .executeUpdate()
                .call(session::flush)
                .onItem().transformToUni(endpointRowCount -> {
                    if (endpointRowCount == 0) {
                        return Uni.createFrom().item(Boolean.FALSE);
                    } else if (endpoint.getProperties() == null || endpoint.getType() != EndpointType.WEBHOOK) {
                        return Uni.createFrom().item(Boolean.TRUE);
                    } else {
                        WebhookAttributes attr = (WebhookAttributes) endpoint.getProperties();
                        return session.createQuery(webhookQuery)
                                .setParameter("url", attr.getUrl())
                                .setParameter("method", attr.getMethod())
                                .setParameter("disableSslVerification", attr.isDisableSSLVerification())
                                .setParameter("secretToken", attr.getSecretToken())
                                .setParameter("endpointId", endpoint.getId())
                                .executeUpdate()
                                .call(session::flush)
                                .onItem().transform(rowCount -> rowCount > 0);
                    }
                });
    }

    private Endpoint mapProperties(Endpoint endpoint) {
        if (endpoint.getProperties() != null) {
            switch (endpoint.getType()) {
                case WEBHOOK:
                    EndpointWebhook webhook = webhook(endpoint.getProperties());
                    webhook.setEndpoint(endpoint);
                    endpoint.setWebhook(webhook);
                    break;
                case EMAIL_SUBSCRIPTION:
                case DEFAULT:
                default:
                    // Do nothing for now
                    break;
            }
        }
        return endpoint;
    }

    private EndpointWebhook webhook(Attributes properties) {
        WebhookAttributes attr = (WebhookAttributes) properties;
        EndpointWebhook webhook = new EndpointWebhook();
        webhook.setUrl(attr.getUrl());
        webhook.setMethod(attr.getMethod());
        webhook.setDisableSslVerification(attr.isDisableSSLVerification());
        webhook.setSecretToken(attr.getSecretToken());
        webhook.setBasicAuthentication(attr.getBasicAuthentication());
        return webhook;
    }
}
