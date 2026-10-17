package dev.hashmark.common.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Havuzdan alınan her bağlantıya o anki {@link TenantContext} kapsamını yazar ({@code app.user_id},
 * {@code app.bypass_rls}). Her alımda **iki değer birden** üzerine yazıldığı için önceki kullanıcının
 * kapsamı bağlantıda kalamaz. Maliyet: alım başına tek kısa sorgu.
 *
 * <p>Transaction içinde bağlantı bir kez alınır; kapsam transaction başındaki değerdir. Bu yüzden
 * {@link TenantContext#asUser}/{@link TenantContext#asSystem} transaction'ı <b>kapsamalıdır</b>, içinde açılmamalıdır.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String APPLY_SQL = "SELECT set_config('app.user_id', ?, false), set_config('app.bypass_rls', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    private static Connection apply(Connection connection) throws SQLException {
        TenantContext.Scope scope = TenantContext.current();
        try (PreparedStatement ps = connection.prepareStatement(APPLY_SQL)) {
            ps.setString(1, scope.userId() == null ? "" : scope.userId().toString());
            ps.setString(2, scope.system() ? "on" : "");
            ps.execute();
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
