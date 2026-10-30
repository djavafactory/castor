package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Optional;

public interface CargoFamilyRepository {

    List<CargoFamily> findAll();

    Optional<CargoFamily> find(String code);
}
