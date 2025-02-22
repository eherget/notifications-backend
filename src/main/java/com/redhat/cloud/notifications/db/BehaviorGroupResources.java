package com.redhat.cloud.notifications.db;

import com.redhat.cloud.notifications.models.BehaviorGroup;
import com.redhat.cloud.notifications.models.BehaviorGroupAction;
import com.redhat.cloud.notifications.models.Bundle;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.EventTypeBehavior;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class BehaviorGroupResources {

    private static final Logger LOGGER = Logger.getLogger(BehaviorGroupResources.class.getName());

    @Inject
    Mutiny.Session session;

    public Uni<BehaviorGroup> create(String accountId, BehaviorGroup behaviorGroup) {
        return Uni.createFrom().item(behaviorGroup)
                .onItem().transform(bg -> {
                    bg.setAccountId(accountId);
                    Bundle bundle = session.getReference(Bundle.class, bg.getBundleId());
                    bg.setBundle(bundle);
                    return bg;
                })
                .onItem().transformToUni(session::persist)
                .call(session::flush)
                .replaceWith(behaviorGroup);
    }

    public Uni<List<BehaviorGroup>> findByBundleId(String accountId, UUID bundleId) {
        // When PostgreSQL sorts a BOOLEAN column in DESC order, true comes first. That's not true for all DBMS.
        String query = "FROM BehaviorGroup b LEFT JOIN FETCH b.actions WHERE b.accountId = :accountId AND b.bundle.id = :bundleId " +
                "ORDER BY b.defaultBehavior DESC, b.created DESC";
        return session.createQuery(query, BehaviorGroup.class)
                .setParameter("accountId", accountId)
                .setParameter("bundleId", bundleId)
                .getResultList();
    }

    // TODO Should this be forbidden for default behavior groups?
    public Uni<Boolean> update(String accountId, BehaviorGroup behaviorGroup) {
        String query = "UPDATE BehaviorGroup SET name = :name, displayName = :displayName WHERE accountId = :accountId AND id = :id";
        return session.createQuery(query)
                .setParameter("name", behaviorGroup.getName())
                .setParameter("displayName", behaviorGroup.getDisplayName())
                .setParameter("accountId", accountId)
                .setParameter("id", behaviorGroup.getId())
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    // TODO Should this be forbidden for default behavior groups?
    public Uni<Boolean> delete(String accountId, UUID behaviorGroupId) {
        String query = "DELETE FROM BehaviorGroup WHERE accountId = :accountId AND id = :id";
        return session.createQuery(query)
                .setParameter("accountId", accountId)
                .setParameter("id", behaviorGroupId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    public Uni<Boolean> addEventTypeBehavior(String accountId, UUID eventTypeId, UUID behaviorGroupId) {
        String query = "SELECT COUNT(*) FROM BehaviorGroup WHERE accountId = :accountId AND id = :id";
        return session.createQuery(query, Long.class)
                .setParameter("accountId", accountId)
                .setParameter("id", behaviorGroupId)
                .getSingleResult()
                .onItem().transform(count -> {
                    if (count == 0L) {
                        throw new NotFoundException("Behavior group not found: " + behaviorGroupId);
                    } else {
                        EventType eventType = session.getReference(EventType.class, eventTypeId);
                        BehaviorGroup behaviorGroup = session.getReference(BehaviorGroup.class, behaviorGroupId);
                        return new EventTypeBehavior(eventType, behaviorGroup);
                    }
                })
                .onItem().transformToUni(session::persist)
                .onItem().call(session::flush)
                .replaceWith(Boolean.TRUE)
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.log(Level.WARNING, "Event type behavior addition failed", failure);
                    return Boolean.FALSE;
                });
    }

    public Uni<Boolean> deleteEventTypeBehavior(String accountId, UUID eventTypeId, UUID behaviorGroupId) {
        String query = "DELETE FROM EventTypeBehavior WHERE eventType.id = :eventTypeId AND behaviorGroup.id = :behaviorGroupId " +
                "AND behaviorGroup.id IN (SELECT id FROM BehaviorGroup WHERE accountId = :accountId)";
        return session.createQuery(query)
                .setParameter("eventTypeId", eventTypeId)
                .setParameter("behaviorGroupId", behaviorGroupId)
                .setParameter("accountId", accountId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    public Uni<List<EventType>> findEventTypesByBehaviorGroupId(String accountId, UUID behaviorGroupId) {
        String query = "SELECT e FROM EventType e LEFT JOIN FETCH e.application JOIN e.behaviors b " +
                "WHERE b.behaviorGroup.accountId = :accountId AND b.behaviorGroup.id = :behaviorGroupId";
        return session.createQuery(query, EventType.class)
                .setParameter("accountId", accountId)
                .setParameter("behaviorGroupId", behaviorGroupId)
                .getResultList();
    }

    public Uni<List<BehaviorGroup>> findBehaviorGroupsByEventTypeId(String accountId, UUID eventTypeId, Query limiter) {
        String query = "SELECT bg FROM BehaviorGroup bg JOIN bg.behaviors b WHERE bg.accountId = :accountId AND b.eventType.id = :eventTypeId";

        if (limiter != null) {
            query = limiter.getModifiedQuery(query);
        }

        Mutiny.Query<BehaviorGroup> mutinyQuery = session.createQuery(query, BehaviorGroup.class)
                .setParameter("accountId", accountId)
                .setParameter("eventTypeId", eventTypeId);

        if (limiter != null && limiter.getLimit() != null && limiter.getLimit().getLimit() > 0) {
            mutinyQuery = mutinyQuery.setMaxResults(limiter.getLimit().getLimit())
                    .setFirstResult(limiter.getLimit().getOffset());
        }

        return mutinyQuery.getResultList()
                .onItem().invoke(behaviorGroups -> behaviorGroups.forEach(BehaviorGroup::filterOutActions));
    }

    public Uni<Boolean> addBehaviorGroupAction(String accountId, UUID behaviorGroupId, UUID endpointId) {
        String query = "SELECT COUNT(*) FROM BehaviorGroup WHERE accountId = :accountId AND id = :id";
        return session.createQuery(query, Long.class)
                .setParameter("accountId", accountId)
                .setParameter("id", behaviorGroupId)
                .getSingleResult()
                .onItem().transform(count -> {
                    if (count == 0L) {
                        throw new NotFoundException("Behavior group not found: " + behaviorGroupId);
                    } else {
                        BehaviorGroup behaviorGroup = session.getReference(BehaviorGroup.class, behaviorGroupId);
                        Endpoint endpoint = session.getReference(Endpoint.class, endpointId);
                        return new BehaviorGroupAction(behaviorGroup, endpoint);
                    }
                })
                .onItem().transformToUni(session::persist)
                .onItem().call(session::flush)
                .replaceWith(Boolean.TRUE)
                .onFailure().recoverWithItem(failure -> {
                    LOGGER.log(Level.WARNING, "Behavior group action addition failed", failure);
                    return Boolean.FALSE;
                });
    }

    public Uni<Boolean> deleteBehaviorGroupAction(String accountId, UUID behaviorGroupId, UUID endpointId) {
        String query = "DELETE FROM BehaviorGroupAction WHERE behaviorGroup.id = :behaviorGroupId AND endpoint.id = :endpointId " +
                "AND behaviorGroup.id IN (SELECT id FROM BehaviorGroup WHERE accountId = :accountId)";
        return session.createQuery(query)
                .setParameter("behaviorGroupId", behaviorGroupId)
                .setParameter("endpointId", endpointId)
                .setParameter("accountId", accountId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    // This should only be called from an internal API. That's why we don't have to validate the accountId.
    public Uni<Boolean> setDefaultBehaviorGroup(UUID bundleId, UUID behaviorGroupId) {
        String query = "UPDATE BehaviorGroup SET defaultBehavior = (CASE WHEN id = :behaviorGroupId THEN TRUE ELSE FALSE END) " +
                "WHERE bundle.id = :bundleId";
        return session.createQuery(query)
                .setParameter("behaviorGroupId", behaviorGroupId)
                .setParameter("bundleId", bundleId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }

    public Uni<Boolean> muteEventType(String accountId, UUID eventTypeId) {
        String query = "DELETE FROM EventTypeBehavior b " +
                "WHERE b.behaviorGroup.id IN (SELECT id FROM BehaviorGroup WHERE accountId = :accountId) AND b.eventType.id = :eventTypeId";
        return session.createQuery(query)
                .setParameter("accountId", accountId)
                .setParameter("eventTypeId", eventTypeId)
                .executeUpdate()
                .call(session::flush)
                .onItem().transform(rowCount -> rowCount > 0);
    }
}
