package it.replica8.castor.core.port.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.Catalogue;
import it.replica8.castor.core.port.domain.Catalogue.AccuracyClassInfo;
import it.replica8.castor.core.port.domain.Catalogue.AssetCategoryInfo;
import it.replica8.castor.core.port.domain.Catalogue.CargoFamilyInfo;
import it.replica8.castor.core.port.domain.Catalogue.CargoType;
import it.replica8.castor.core.port.domain.Catalogue.PaletteGroupInfo;
import it.replica8.castor.core.port.domain.Catalogue.ShipClass;
import it.replica8.castor.core.port.domain.Catalogue.SurfaceInfo;
import it.replica8.castor.core.port.domain.CatalogueRepository;

/** Reads the lookup tables of schema ref. */
@ApplicationScoped
public class JdbcCatalogueRepository implements CatalogueRepository {

    private final DataSource dataSource;

    public JdbcCatalogueRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Catalogue load() {
        try (Connection c = dataSource.getConnection()) {
            return new Catalogue(
                    cargoTypes(c),
                    shipClasses(c),
                    rows(
                            c,
                            "select code, label from ref.cargo_family order by code",
                            rs -> new CargoFamilyInfo(rs.getString(1), rs.getString(2))),
                    rows(
                            c,
                            "select code, label, colour_hex from ref.surface order by code",
                            rs -> new SurfaceInfo(rs.getString(1), rs.getString(2), rs.getString(3))),
                    rows(
                            c,
                            "select code, label, colour_hex, sort_order from ref.accuracy_class order by sort_order",
                            rs -> new AccuracyClassInfo(
                                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))),
                    rows(
                            c,
                            "select code, label, sort_order from ref.asset_category order by sort_order",
                            rs -> new AssetCategoryInfo(rs.getString(1), rs.getString(2), rs.getInt(3))),
                    rows(
                            c,
                            "select code, label, sort_order from ref.palette_group order by sort_order",
                            rs -> new PaletteGroupInfo(rs.getString(1), rs.getString(2), rs.getInt(3))),
                    Catalogue.DENSITY_NOTE);
        } catch (SQLException e) {
            throw new IllegalStateException("Reading the catalogue failed", e);
        }
    }

    private static List<CargoType> cargoTypes(Connection c) throws SQLException {
        return rows(
                c,
                "select code, name, family_code, base_unit, density_t_m3, storage_form, max_stack_m, repose_deg, "
                        + "colour_hex from ref.cargo_type order by code",
                rs -> new CargoType(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getBigDecimal(5),
                        rs.getString(6),
                        rs.getBigDecimal(7),
                        rs.getBigDecimal(8),
                        rs.getString(9)));
    }

    private static List<ShipClass> shipClasses(Connection c) throws SQLException {
        return rows(
                c,
                "select code, name, kind, loa_m, beam_m, draft_m, dwt_t from ref.ship_class order by dwt_t",
                rs -> new ShipClass(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getBigDecimal(6),
                        rs.getInt(7)));
    }

    private static <T> List<T> rows(Connection c, String sql, JdbcQueries.RowMapper<T> mapper) throws SQLException {
        return JdbcQueries.query(c, sql, ps -> {}, mapper);
    }
}
