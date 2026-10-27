package it.replica8.castor.core.port.infrastructure;

import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.Port;
import it.replica8.castor.core.port.domain.PortRepository;

@ApplicationScoped
public class JdbcPortRepository implements PortRepository {

    private final DataSource dataSource;

    public JdbcPortRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Port> findByCode(String code) {
        var sql = "select code, name, synthetic, ST_X(origin), ST_Y(origin), frame_rot_deg, measure_srid "
                + "from ref.port where code = lower(?)";
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Port(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getBoolean(3),
                        rs.getDouble(4),
                        rs.getDouble(5),
                        rs.getDouble(6),
                        rs.getInt(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading port " + code + " failed", e);
        }
    }
}
