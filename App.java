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
            if (path.equals("/undone") && method.equals("GET")) {
                Integer id = queryId(exchange);
                if (id != null) {
                    try {
                        markUndone(id); // ★ 未達操作でdoneを0に戻す
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
                redirect(exchange);
                return;
            }            if (path.equals("/delete") && method.equals("GET")) {
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
            if (path.equals("/event") && method.equals("GET")) {
                String choice = queryValue(exchange, "choice"); // ★ イベントの選択肢を受け取る
                byte[] body = eventMessage(choice).getBytes(StandardCharsets.UTF_8); // ★ 選択結果を日本語で返す
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); // ★ UTF-8で表示する
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                return;
            }
            if (path.equals("/attitude") && method.equals("GET")) { // ★ ボタンを押したときだけセリフを表示する
                byte[] body; // ★ セリフ表示用のHTMLを用意する
                try {
                    body = selectTodos(true).getBytes(StandardCharsets.UTF_8); // ★ セリフ表示を有効にする
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); // ★ UTF-8で表示する
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                return;
            }
            if (path.equals("/") && method.equals("GET")) {
                byte[] body; // ★ SELECT結果を表示する
                try {
                    body = selectTodos(false).getBytes(StandardCharsets.UTF_8); // ★ 通常表示ではセリフを表示しない
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
    private static void markUndone(int id) throws SQLException { // ★ Todoを未達状態に戻す
        String sql = "UPDATE todos SET done = 0 WHERE id = ?"; // ★ 未達用のUPDATE文を用意する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使う
            statement.setInt(1, id);
            statement.executeUpdate(); // ★ doneを0へ更新する
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

    private static String selectTodos(boolean showAttitude) throws SQLException { // ★ セリフ表示の有無を受け取る
        String sql = "SELECT id, title, done FROM todos ORDER BY id";
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;font-family:'Comic Sans MS','Segoe Print',cursive;color:#f4f7ff;background:radial-gradient(circle at 50% 35%,#30466f 0,#111a2e 55%,#050914 100%);}.todo-panel{position:relative;display:flex;flex-direction:column;box-sizing:border-box;width:min(70vw,1200px);height:min(78vh,760px);min-height:620px;padding:48px;border:1px solid #6d83ae;border-radius:24px;background:rgba(9,16,34,.78);box-shadow:0 20px 60px rgba(0,0,0,.45);overflow:hidden;}form{display:flex;justify-content:center;align-items:center;gap:38px;}input,button{height:42px;box-sizing:border-box;border-radius:999px;font:inherit;}input{width:min(50vw,420px);padding:0 16px;border:2px solid #f5a9c4;background:#ffe4ec;color:#351b28;}button{padding:0 22px;border:2px solid #e889aa;background:#f5a9c4;color:#351b28;cursor:pointer;}button:hover{background:#ffc1d7;}ul.todo-list{flex:0 0 300px;width:100%;height:300px;box-sizing:border-box;overflow-y:auto;overflow-x:hidden;list-style:none;padding:0 8px;margin:28px 0;}li.todo-item{display:flex;align-items:flex-start;gap:20px;width:100%;margin:12px 0;text-align:left;}span.todo-title{flex:1;min-width:0;overflow-wrap:anywhere;line-height:1.6;font-size:1.2em;}span.todo-actions{display:flex;flex-shrink:0;gap:8px;margin-left:auto;white-space:nowrap;}span.todo-actions a,.attitude-link{color:#ffd6f5;text-decoration:none;font-weight:bold;}span.todo-actions a:hover,.attitude-link:hover{color:#ffffff;text-decoration:underline;}.todo-summary{position:absolute;left:48px;right:48px;bottom:48px;width:auto;text-align:center;z-index:2;}</style></head><body><main class='todo-panel'>"); // ★ 枠と一覧の高さを固定し、Todo文字を1.2倍にする
        html.append("<h1>今日は何するのかおしえて</h1><form method='post' action='/add'><input name='todo'><button>追加</button></form><ul class='todo-list'>"); // ★ 固定高さのTodo一覧エリアを使う
        int total = 0; // ★ 登録Todo総数を数える
        int completed = 0; // ★ 完了Todo数を数える
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                int id = resultSet.getInt("id");
                String title = escapeHtml(resultSet.getString("title"));
                boolean done = resultSet.getInt("done") == 1;
                if (done) completed++; // ★ 完了数を加算する
                total++; // ★ 登録数を加算する
                String mark = done ? " ✓" : "";
                html.append("<li class='todo-item'><span class='todo-title'>").append(title).append(mark)
                        .append("</span><span class='todo-actions'>")
                        .append("<a href='/done?id=").append(id).append("'>完了</a>")
                        .append("<a href='/undone?id=").append(id).append("'>未達</a>")
                        .append("<a href='/delete?id=").append(id).append("'>削除</a></span></li>"); // ★ Todo本文を左、操作ボタンを右へ固定する
            }
        }
        int rate = total == 0 ? 0 : completed * 100 / total; // ★ 完了数÷登録数×100で達成率を計算する
        html.append("</ul><section class='todo-summary'><p>達成率: ").append(rate).append("%</p>"); // ★ 達成率とリンクを枠下部の固定領域へ置く
        html.append("<p class='attitude-prompt'><a class='attitude-link' href='/attitude'>♡♡♡なにか言いたがっています♡♡♡</a></p>"); // ★ 達成率の直下に案内リンクを中央表示する
        if (showAttitude) { // ★ ボタン経由のときだけセリフを表示する
            html.append("<p>").append(randomAttitude(rate)).append("</p>"); // ★ 達成率に応じたセリフをランダム表示する
        }
        if (total > 0 && completed == total) { // ★ 0件では発生させず、全件完了時だけイベントを出す
            html.append("<h2>俺とTodo、どっちが大事なの？</h2>"); // ★ 特別イベントの質問を表示する
            html.append("<a href='/event?choice=you'>あなた</a> "); // ★ 選択肢1を表示する
            html.append("<a href='/event?choice=todo'>Todo</a> "); // ★ 選択肢2を表示する
            html.append("<a href='/event?choice=work'>とりあえず働いて</a>"); // ★ 選択肢3を表示する
        }
        return html.append("</section></main></body></html>").toString(); // ★ 固定サマリー領域を閉じる
    }

    private static String randomAttitude(int rate) { // ★ 達成率別に複数のセリフから選ぶ
        String[] lines;
        if (rate < 25) {
            lines = new String[]{ // ★ 未達時のセリフを20個追加する
                    "俺がいないと何もできないんだね。",
                    "まあ、俺が見ててあげるよ。",
                    "今日も何もできなかったんだ。僕がいないからかな。",
                    "そんなに遅くまで何してたの？僕に言えないこと？",
                    "約束したよね。どうして守れないの？",
                    "僕なら君のこと、ちゃんと見てるのに。",
                    "別に怒ってないよ。君がどうでもいいならね。",
                    "へえ、僕には連絡しないでそれはできるんだ。",
                    "頑張れないなら、最初から言えばよかったのに。",
                    "君のためを思って言ってるんだよ。わかってる？",
                    "僕を心配させるの、そんなに楽しい？",
                    "また後回しにしたんだ。僕のことも同じかな。",
                    "誰といるの？返事が遅いと不安になるんだけど。",
                    "僕以外のことばかり優先するんだね。",
                    "できない理由はすぐ出てくるのに、僕への言い訳はないんだ。",
                    "今日は僕のそばにいてくれると思ってた。違ったんだね。",
                    "ねえ、僕がいなくても平気なふりしないでよ。",
                    "そんな顔するなら、もう何も言わない。……本当に言わないから。",
                    "君が頑張らないと、僕まで不安になるんだよ。",
                    "もう寝たの？僕を置いて先に寝るなんて寂しいな。",
                    "……ごめん、ちょっと寂しくなっただけ。僕のこと嫌いになった？",
                    "未達のままなのに、僕には大丈夫って言うんだね。"
            };
        } else if (rate < 50) {
            lines = new String[]{"最近ちょっと調子乗ってない？", "その程度で頑張ったつもり？"};
        } else if (rate < 75) {
            lines = new String[]{"そんなに頑張って、誰に褒められたいの？", "僕以外の人に認められたいんだね。"};
        } else {
            lines = new String[]{"ねえ、俺を置いていかないでよ。", "君がいないと、僕は何もできない。"};
        }
        return lines[(int) (Math.random() * lines.length)]; // ★ セリフをランダムに選ぶ
    }

    private static String eventMessage(String choice) { // ★ 全件完了イベントの選択結果に応じたセリフを返す
        String message;
        if ("you".equals(choice)) {
            message = "あなた？ そんな答えを選ぶなんて、僕のこと全然見てないんだね。";
        } else if ("todo".equals(choice)) {
            message = "Todoを選ぶの？ 僕より予定表を大事にするなんてひどいよ。";
        } else {
            message = "働けって言うの？ じゃあ君が僕の分まで全部やってよ。";
        }
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;font-family:'Comic Sans MS','Segoe Print',cursive;color:#f4f7ff;background:radial-gradient(circle at 50% 35%,#30466f 0,#111a2e 55%,#050914 100%);}.todo-panel{position:relative;display:flex;flex-direction:column;box-sizing:border-box;width:min(70vw,1200px);min-height:70vh;padding:48px;border:1px solid #6d83ae;border-radius:24px;background:rgba(9,16,34,.78);box-shadow:0 20px 60px rgba(0,0,0,.45);display:flex;flex-direction:column;justify-content:center;align-items:center;}.attitude-message{font-size:1.5rem;line-height:1.8;max-width:90%;}.attitude-link{color:#ffd6f5;text-decoration:none;font-weight:bold;}.attitude-link:hover{color:#ffffff;text-decoration:underline;}</style></head><body><main class='todo-panel'>"
                + "<p class='attitude-message'>" + message + "</p><a class='attitude-link' href='/'>Todo一覧へ戻る</a></main></body></html>"; // ★ セリフ画面を一覧画面と同じ背景・枠・文字色に統一する
    }
    private static String queryValue(HttpExchange exchange, String key) { // ★ URLクエリの値をUTF-8で取得する
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith(key + "=")) {
                    return URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
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
