package com.djavafactory.castor.core.identity.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.identity.domain.Role;
import com.djavafactory.castor.core.identity.domain.UserRepository;
import com.djavafactory.castor.core.identity.domain.UserSummary;

@ApplicationScoped
public class JpaUserRepository implements UserRepository {

    private final EntityManager em;

    public JpaUserRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public Optional<UserSummary> findByUsername(String username) {
        return em.createQuery("select u from UserAccount u where lower(u.username) = :name", UserAccount.class)
                .setParameter("name", username.toLowerCase())
                .getResultStream()
                .findFirst()
                .map(u -> new UserSummary(u.username, u.displayName, Set.copyOf(u.roles)));
    }

    @Override
    @Transactional
    public List<UserSummary> findReviewers() {
        return em
                .createQuery(
                        "select distinct u from UserAccount u join u.roles r "
                                + "where u.enabled = true and r in :roles order by u.username",
                        UserAccount.class)
                .setParameter("roles", List.of(Role.PUBLISHER, Role.ADMIN))
                .getResultList()
                .stream()
                .map(u -> new UserSummary(u.username, u.displayName, Set.copyOf(u.roles)))
                .toList();
    }
}
