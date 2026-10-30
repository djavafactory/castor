package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoFamily;
import com.djavafactory.castor.core.operations.domain.CargoFamilyRepository;

/** ref.cargo_family: the families are data, so a new one is a row and needs no release. */
@ApplicationScoped
public class JdbcCargoFamilyRepository implements CargoFamilyRepository {

    private static final String SELECT = "select code, label, default_unit from ref.cargo_family ";

    private final OpsSql sql;

    JdbcCargoFamilyRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public List<CargoFamily> findAll() {
        return sql.query(SELECT + "order by code", JdbcCargoFamilyRepository::map);
    }

    @Override
    public Optional<CargoFamily> find(String code) {
        return sql.queryOne(SELECT + "where code = ?", JdbcCargoFamilyRepository::map, code);
    }

    private static CargoFamily map(OpsSql.Row r) {
        return new CargoFamily(r.text("code"), r.text("label"), Unit.parse(r.text("default_unit")));
    }
}
