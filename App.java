import com.sun.net.httpserver.HttpExchange; // ★ HTTPリクエストを扱う
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection; // ★ SQLite接続を扱う
import java.sql.DriverManager; // ★ SQLiteへ接続する
import java.sql.PreparedStatement; // ★ SQLを安全に実行する
import java.sql.ResultSet; // ★ SELECT結果を扱う
import java.sql.SQLException; // ★ SQL例外を扱う

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db"; // ★ SQLiteデータベースへ接続する

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC"); // ★ SQLite JDBCドライバを明示的に登録する
        initDatabase(); // ★ 起動時にtodos表を用意する
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = URLDecoder.decode(body.substring(5), StandardCharsets.UTF_8);
                if (!title.isEmpty()) {
                    try {
                        insertTodo(title); // ★ INSERTでTodoを追加する
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
                redirect(exchange);
                return;
            }
            if (path.equals("/done") && method.equals("GET")) {
                Integer id = queryId(exchange);
                if (id != null) {
                    try {
                        updateTodo(id); // ★ UPDATEでTodoを完了にする
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
                redirect(exchange);
                return;
            }
            if (path.equals("/delete") && method.equals("GET")) {
                Integer id = queryId(exchange);
                if (id != null) {
                    try {
                        deleteTodo(id); // ★ DELETEでTodoを削除する
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
                redirect(exchange);
                return;
            }
            if (path.equals("/") && method.equals("GET")) {
                byte[] body; // ★ SELECT結果を表示する
                try {
                    body = selectTodos().getBytes(StandardCharsets.UTF_8); // ★ SELECT結果を一覧表示する
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:8080");
    }

    private static Connection connect() throws SQLException { // ★ SQLiteへ接続する
        return DriverManager.getConnection(DB_URL); // ★ jdbc:sqlite:todos.dbへ接続する
    }

    private static void initDatabase() throws SQLException { // ★ SQLite用のテーブルを初期化する
        String sql = "CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, title TEXT, done INTEGER)"; // ★ todos表を作成する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ SQLを準備する
            statement.executeUpdate(); // ★ CREATE TABLEを実行する
        }
    }

    private static void insertTodo(String title) throws SQLException { // ★ TodoをSQLiteへ追加する
        String idSql = "SELECT COALESCE(MAX(id), 0) + 1 FROM todos"; // ★ 次のidをSELECTする
        String insertSql = "INSERT INTO todos (id, title, done) VALUES (?, ?, ?)"; // ★ INSERT文を用意する
        try (Connection connection = connect();
                PreparedStatement idStatement = connection.prepareStatement(idSql);
                ResultSet resultSet = idStatement.executeQuery();
                PreparedStatement insertStatement = connection.prepareStatement(insertSql)) { // ★ PreparedStatementを使う
            resultSet.next();
            insertStatement.setInt(1, resultSet.getInt(1));
            insertStatement.setString(2, title);
            insertStatement.setInt(3, 0);
            insertStatement.executeUpdate(); // ★ INSERTを実行する
        }
    }

    private static void updateTodo(int id) throws SQLException { // ★ TodoをSQLite上で完了にする
        String sql = "UPDATE todos SET done = 1 WHERE id = ?"; // ★ UPDATE文を用意する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使う
            statement.setInt(1, id);
            statement.executeUpdate(); // ★ UPDATEを実行する
        }
    }

    private static void deleteTodo(int id) throws SQLException { // ★ TodoをSQLiteから削除する
        String sql = "DELETE FROM todos WHERE id = ?"; // ★ DELETE文を用意する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使う
            statement.setInt(1, id);
            statement.executeUpdate(); // ★ DELETEを実行する
        }
    }

    private static String selectTodos() throws SQLException { // ★ SELECTでTodo一覧を取得する
        String sql = "SELECT id, title, done FROM todos ORDER BY id"; // ★ SELECT文を用意する
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body>");
        html.append("<h1>Todo</h1><form method='post' action='/add'><input name='todo'><button>追加</button></form><ul>");
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql); // ★ PreparedStatementでSELECTする
                ResultSet resultSet = statement.executeQuery()) { // ★ SELECTを実行する
            while (resultSet.next()) {
                int id = resultSet.getInt("id");
                String title = escapeHtml(resultSet.getString("title"));
                String mark = resultSet.getInt("done") == 1 ? " ✓" : "";
                html.append("<li>").append(title).append(mark)
                        .append(" <a href='/done?id=").append(id).append("'>完了</a>")
                        .append(" <a href='/delete?id=").append(id).append("'>削除</a></li>");
            }
        }
        return html.append("</ul></body></html>").toString(); // ★ SELECT結果をHTMLとして返す
    }

    private static Integer queryId(HttpExchange exchange) { // ★ クエリのidを取得する
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.startsWith("id=") || query.length() <= 3) return null;
        try {
            return Integer.parseInt(query.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void redirect(HttpExchange exchange) throws IOException { // ★ 一覧へ戻す
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static String escapeHtml(String value) { // ★ 表示時のHTML特殊文字を無害化する
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
