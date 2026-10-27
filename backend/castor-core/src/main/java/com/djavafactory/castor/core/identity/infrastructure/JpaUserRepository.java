package com.djavafactory.castor.core.identity.infrastructure;

import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

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
}
