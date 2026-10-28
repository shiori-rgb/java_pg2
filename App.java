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
                redirectAfterAdd(exchange);
                return;
            }
            if (path.equals("/bulk-delete") && method.equals("POST")) {
                try {
                    deleteAllTodos(); // ★ 確認後の一括削除を実行する
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                redirectAfterDelete(exchange); // ★ 削除後はコメントなしの一覧へ戻す
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
                redirectAfterStatus(exchange, "done");
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
                redirectAfterStatus(exchange, "undone");
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
                redirectAfterDelete(exchange);
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
                    String attitudeType = queryValue(exchange, "type"); // ★ 完了・未達のセリフ分類を受け取る
                    boolean eventRequest = "1".equals(queryValue(exchange, "event")); // ★ イベント専用リンクからの表示要求を受け取る
                    body = selectTodos(true, false, attitudeType, eventRequest).getBytes(StandardCharsets.UTF_8); // ★ セリフ表示を有効にする
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
                    String attitudeType = queryValue(exchange, "attitudeType"); // ★ 完了・未達の種別を一覧へ引き継ぐ
                    boolean quiet = "1".equals(queryValue(exchange, "deleted")) || "1".equals(queryValue(exchange, "added")); // ★ 追加・削除直後だけ下部リンクを隠す
                    body = selectTodos(false, quiet, attitudeType, false).getBytes(StandardCharsets.UTF_8); // ★ 一覧ではコメントを表示しない
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
            try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE todos ADD COLUMN operated INTEGER NOT NULL DEFAULT 0")) {
                alter.executeUpdate(); // ★ 既存Todoへ操作済み状態を追加する
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column name")) throw e; // ★ 既存列なら処理を継続する
            }
            try (PreparedStatement stateTable = connection.prepareStatement("CREATE TABLE IF NOT EXISTS app_state (id INTEGER PRIMARY KEY, event_shown INTEGER NOT NULL DEFAULT 0)")) {
                stateTable.executeUpdate(); // ★ 隠しイベント状態表を用意する
            }
            try (PreparedStatement stateRow = connection.prepareStatement("INSERT OR IGNORE INTO app_state (id, event_shown) VALUES (1, 0)")) {
                stateRow.executeUpdate(); // ★ 隠しイベント未表示状態を用意する
            }
        }
    }

    private static void insertTodo(String title) throws SQLException { // ★ TodoをSQLiteへ追加する
        String idSql = "SELECT COALESCE(MAX(id), 0) + 1 FROM todos"; // ★ 次のidをSELECTする
        String insertSql = "INSERT INTO todos (id, title, done, operated) VALUES (?, ?, ?, ?)"; // ★ INSERT文を用意する
        try (Connection connection = connect();
                PreparedStatement idStatement = connection.prepareStatement(idSql);
                ResultSet resultSet = idStatement.executeQuery();
                PreparedStatement insertStatement = connection.prepareStatement(insertSql)) { // ★ PreparedStatementを使う
            resultSet.next();
            insertStatement.setInt(1, resultSet.getInt(1));
            insertStatement.setString(2, title);
            insertStatement.setInt(3, 0);
            insertStatement.setInt(4, 0);
            insertStatement.executeUpdate(); // ★ INSERTを実行する
        }
    }

    private static void updateTodo(int id) throws SQLException { // ★ TodoをSQLite上で完了にする
        String sql = "UPDATE todos SET done = 1, operated = 1 WHERE id = ?"; // ★ UPDATE文を用意する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使う
            statement.setInt(1, id);
            statement.executeUpdate(); // ★ UPDATEを実行する
        }
    }
    private static void markUndone(int id) throws SQLException { // ★ Todoを未達状態に戻す
        String sql = "UPDATE todos SET done = 0, operated = 1 WHERE id = ?"; // ★ 未達用のUPDATE文を用意する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使う
            statement.setInt(1, id);
            statement.executeUpdate(); // ★ doneを0へ更新する
        }
    }

    private static boolean isEventShown() throws SQLException {
        String sql = "SELECT event_shown FROM app_state WHERE id = 1";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt("event_shown") == 1;
        }
    }

    private static void markEventShown() throws SQLException {
        String sql = "UPDATE app_state SET event_shown = 1 WHERE id = 1";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
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

    private static void deleteAllTodos() throws SQLException { // ★ 確認済みのTodoを全件削除する
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM todos")) {
            statement.executeUpdate(); // ★ todos表のTodoを全件削除する
        }
    }

    private static String selectTodos(boolean showAttitude, boolean hidePrompt, String attitudeType, boolean eventRequest) throws SQLException { // ★ セリフとイベント表示の有無を受け取る
        String sql = "SELECT id, title, done, operated FROM todos ORDER BY id";
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;font-family:'Comic Sans MS','Segoe Print',cursive;color:#f4f7ff;background:radial-gradient(circle at 50% 35%,#30466f 0,#111a2e 55%,#050914 100%);}.todo-panel{position:relative;display:flex;flex-direction:column;box-sizing:border-box;width:min(70vw,1200px);height:min(78vh,760px);min-height:620px;padding:48px;border:1px solid #6d83ae;border-radius:24px;background:rgba(9,16,34,.78);box-shadow:0 20px 60px rgba(0,0,0,.45);overflow:hidden;}form{display:flex;justify-content:center;align-items:center;gap:38px;}input,button{height:42px;box-sizing:border-box;border-radius:999px;font:inherit;}input{width:min(50vw,420px);padding:0 16px;border:2px solid #f5a9c4;background:#ffe4ec;color:#351b28;}input:focus{background:#ffe4ec;outline:2px solid #f5a9c4;outline-offset:2px;}input:-webkit-autofill,input:-webkit-autofill:hover,input:-webkit-autofill:focus{border:2px solid #f5a9c4;-webkit-text-fill-color:#351b28;-webkit-box-shadow:0 0 0 1000px #ffe4ec inset;box-shadow:0 0 0 1000px #ffe4ec inset;}button{padding:0 22px;border:2px solid #e889aa;background:#f5a9c4;color:#351b28;cursor:pointer;}button:hover{background:#ffc1d7;}ul.todo-list::-webkit-scrollbar{width:6px}ul.todo-list::-webkit-scrollbar-track{background:transparent}ul.todo-list::-webkit-scrollbar-thumb{background:#f5a9c4;border-radius:999px}ul.todo-list::-webkit-scrollbar-thumb:hover{background:#ffc1d7}ul.todo-list{position:relative;z-index:1;flex:0 0 288px;width:100%;height:288px;box-sizing:border-box;overflow-y:auto;overflow-x:hidden;scrollbar-width:thin;scrollbar-color:#f5a9c4 transparent;list-style:none;padding:0 8px 12px;margin:28px 0 18px;}ul.todo-list::after{content:'';display:block;height:8px;}li.todo-item{display:flex;box-sizing:border-box;align-items:center;gap:20px;width:100%;height:46px;min-height:46px;margin:0;padding:0;text-align:left;overflow:hidden;}span.todo-title{display:block;flex:1;width:100%;min-width:0;overflow-wrap:anywhere;line-height:1.6;font-size:1.2em;}span.todo-title.done{text-decoration:line-through;text-decoration-skip-ink:none;}span.todo-actions{display:flex;flex-shrink:0;gap:8px;margin-left:auto;white-space:nowrap;}span.todo-actions a,.attitude-link{color:#ffd6f5;text-decoration:none;font-weight:bold;}span.todo-actions a:hover,.attitude-link:hover{color:#ffffff;text-decoration:underline;}.event-prompt{margin:8px 0 0;}.event-link{display:inline-flex;align-items:center;justify-content:center;min-height:36px;padding:0 18px;border:1px solid #F5C8D9;border-radius:999px;background:#E6B2C8;color:#29273A;text-decoration:none;font-size:15px;font-weight:normal;white-space:nowrap;}.event-link:hover{filter:brightness(1.08);}.todo-summary{position:absolute;left:48px;right:48px;bottom:48px;width:auto;text-align:center;background:rgba(9,16,34,.78);z-index:2;}.event-question{margin:18px 0 10px;font-size:1.25em;}.event-options{display:flex;justify-content:center;align-items:center;gap:10px;flex-wrap:nowrap;}.event-options a{display:inline-flex;align-items:center;justify-content:center;height:36px;min-width:80px;padding:0 14px;border-radius:999px;color:#29273A;text-decoration:none;font-size:14px;font-weight:normal;white-space:nowrap;}.event-options a:nth-child(1){background:#E6B2C8;}.event-options a:nth-child(2){background:#C6B5D8;}.event-options a:nth-child(3){background:#B9CCE0;}.event-options a:hover{filter:brightness(1.08);}</style><script>function saveTodoScroll(){var l=document.querySelector('.todo-list');if(l){sessionStorage.setItem('todoListScrollTop',l.scrollTop)}sessionStorage.setItem('pageScrollTop',window.scrollY)}window.addEventListener('load',function(){var l=document.querySelector('.todo-list');var t=sessionStorage.getItem('todoListScrollTop');var p=sessionStorage.getItem('pageScrollTop');if(l&&t!==null){l.scrollTop=Number(t)}if(p!==null){window.scrollTo(0,Number(p))}});document.addEventListener('click',function(e){if(e.target.closest('span.todo-actions a')){saveTodoScroll()}});document.addEventListener('submit',function(){saveTodoScroll()});</script></head><body><main class='todo-panel'>"); // ★ 枠と一覧の高さを固定し、Todo文字を1.2倍にする
        html.append("<style>.help-button{position:absolute;top:18px;right:18px;width:32px;height:32px;padding:0;border:1px solid #F5C8D9;border-radius:50%;background:#E6B2C8;color:#29273A;font-size:18px;line-height:30px;cursor:pointer;z-index:4;}.help-button:hover{filter:brightness(1.08);}.help-overlay{display:none;position:absolute;inset:0;z-index:3;box-sizing:border-box;overflow-y:auto;padding:42px 48px;background:#101A30;border-radius:24px;}.help-overlay.open{display:flex;align-items:center;justify-content:center;}.help-panel{width:min(760px,100%);box-sizing:border-box;padding:34px 42px;text-align:center;}.help-title{margin:0 0 22px;color:#F9D5E3;font-size:32px;line-height:1.3;}.help-concept{margin:0 auto 28px;color:#FFFFFF;font-size:20px;line-height:1.8;white-space:pre-line;}.help-heading{margin:0 0 12px;color:#F9D5E3;font-size:18px;}.help-steps{margin:0 auto 28px;color:#D6DCE7;font-size:14px;line-height:2;text-align:left;width:max-content;max-width:100%;}.help-action{display:inline-flex;align-items:center;justify-content:center;min-height:40px;padding:0 24px;border:1px solid #F5C8D9;border-radius:999px;background:#E6B2C8;color:#29273A;font-size:16px;text-decoration:none;cursor:pointer;}.help-action:hover{filter:brightness(1.08);}.reopen-help .help-start{display:none;}.first-help .help-close{display:none;}</style><button id='help-button' class='help-button' type='button' aria-label='操作説明を表示'>？</button><section id='help-overlay' class='help-overlay first-help' aria-label='アプリの説明'><div class='help-panel'><h1 class='help-title'>ToDoか、俺か。</h1><p class='help-concept'>モラハラ気質・束縛魔。おまけにニート。\n\nそんな彼は、あなたがTodoを達成しても、失敗しても、何か言いたいようです。\n\n達成したほうがいいのか、しないほうがいいのか迷ってしまう、めんどくさい彼氏の言動を楽しみながら、今日の予定を管理しましょう。</p><h2 class='help-heading'>あそびかた</h2><div class='help-steps'>1. やることを入力して「追加」<br>2. 達成したら「完了」、できなかったら「未達」<br>3. 「削除」で項目を消せます。<br>4. 気になる彼のメッセージをクリック！<br>5. 達成率100％でなにかあるかも…？</div><button id='help-start' class='help-action' type='button'>♡ クリックしてはじめる ♡</button><button id='help-close' class='help-action' type='button'>閉じる</button></div></section><script>(function(){var overlay=document.getElementById('help-overlay');var helpButton=document.getElementById('help-button');var startButton=document.getElementById('help-start');var closeButton=document.getElementById('help-close');function openHelp(reopen){overlay.classList.toggle('first-help',!reopen);overlay.classList.toggle('reopen-help',reopen);overlay.classList.add('open');}helpButton.addEventListener('click',function(){openHelp(true);});startButton.addEventListener('click',function(){localStorage.setItem('todoHelpSeen','1');overlay.classList.remove('open');});closeButton.addEventListener('click',function(){overlay.classList.remove('open');});if(localStorage.getItem('todoHelpSeen')!=='1'){openHelp(false);}else{overlay.classList.remove('open');}})();</script><h1>今日は何するのかおしえて</h1><form method='post' action='/add'><input name='todo'><button>追加</button></form><ul class='todo-list'>"); // ★ 初回説明・再表示ヘルプを追加する
        html.append("<style>.help-concept{margin-bottom:22px;}.help-concept span{display:block;}.help-concept span+span{margin-top:12px;}.help-panel{display:flex;flex-direction:column;align-items:center;}.help-action{width:220px;height:56px;min-height:56px;box-sizing:border-box;padding:0;background:transparent;border:2px solid #FFFFFF;color:#FFFFFF;font-size:16px;border-radius:999px;}.first-help .help-close,.reopen-help .help-close{display:inline-flex;}</style><script>(function(){var overlay=document.getElementById('help-overlay');var concept=document.querySelector('.help-concept');var start=document.getElementById('help-start');var close=document.getElementById('help-close');if(concept){concept.innerHTML='<span>モラハラ気質・束縛魔。おまけにニート。</span><span>そんな彼は、あなたがTodoを達成しても、<br>失敗しても、何か言いたいようです。</span><span>達成したほうがいいのか、しないほうがいいのか迷ってしまう、<br>めんどくさい彼氏の言動を楽しみながら、今日の予定を管理しましょう。</span>';}if(start){start.remove();}if(close){close.addEventListener('click',function(){localStorage.setItem('todoHelpSeen','1');overlay.classList.remove('open');});}})();</script>"); // ★ 説明文を3まとまりにし、閉じるボタンを中央配置する
        html.append("<style>.event-options a{background:transparent;border:2px solid #FFFFFF;color:#FFFFFF;}.event-options a:hover{background:transparent;border-color:#FFFFFF;color:#FFFFFF;}.bulk-delete-wrap{display:flex;justify-content:flex-end;width:100%;height:30px;margin:-8px 8px 0 0;}.bulk-delete-button{height:28px;padding:0 12px;border:1px solid #E6B2C8;border-radius:999px;background:transparent;color:#E6B2C8;font-size:12px;cursor:pointer;}.bulk-delete-button:disabled{opacity:.45;cursor:not-allowed;}.bulk-delete-confirm{display:none;position:absolute;inset:0;z-index:5;box-sizing:border-box;align-items:center;justify-content:center;background:rgba(16,26,48,.97);border-radius:24px;}.bulk-delete-confirm.open{display:flex;}.bulk-delete-confirm-panel{text-align:center;color:#FFFFFF;}.bulk-delete-confirm-panel p{font-size:20px;margin:0 0 24px;}.bulk-delete-confirm-panel form{display:inline-flex;gap:10px;}.bulk-confirm,.bulk-cancel{height:36px;padding:0 18px;border-radius:999px;font-size:14px;cursor:pointer;}.bulk-confirm{border:1px solid #E6B2C8;background:#E6B2C8;color:#29273A;}.bulk-cancel{border:1px solid #FFFFFF;background:transparent;color:#FFFFFF;}</style>"); // ★ 隠しイベント選択肢を白枠にし、一括削除の表示を追加する
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
                String titleClass = done ? "todo-title done" : "todo-title"; // ★ 完了時だけTodo本文に取り消し線クラスを付ける
                html.append("<li class='todo-item'><span class='").append(titleClass).append("'>").append(title)
                        .append("</span><span class='todo-actions'>")
                        .append("<a href='/done?id=").append(id).append("'>完了</a>")
                        .append("<a href='/undone?id=").append(id).append("'>未達</a>")
                        .append("<a href='/delete?id=").append(id).append("'>削除</a></span></li>"); // ★ Todo本文を左、操作ボタンを右へ固定する
            }
        }
        int rate = total == 0 ? 0 : completed * 100 / total; // ★ 完了数÷登録数×100で達成率を計算する
        html.append("</ul><div class='bulk-delete-wrap'><button class='bulk-delete-button' type='button'").append(total == 0 ? " disabled" : "").append(" onclick=\"document.getElementById('bulk-delete-confirm').classList.add('open')\">すべて削除</button></div><div id='bulk-delete-confirm' class='bulk-delete-confirm'><div class='bulk-delete-confirm-panel'><p>すべてのTodoを削除しますか？</p><form method='post' action='/bulk-delete'><button class='bulk-confirm' type='submit'>削除する</button></form><button class='bulk-cancel' type='button' onclick=\"document.getElementById('bulk-delete-confirm').classList.remove('open')\">キャンセル</button></div></div><section class='todo-summary'><p>達成率: ").append(rate).append("%</p>"); // ★ 一括削除ボタンと確認画面を一覧下部に追加する
        if (!hidePrompt) {
            String attitudeLink = attitudeType.isEmpty() ? "/attitude" : "/attitude?type=" + attitudeType; // ★ 完了・未達の種別をリンクへ引き継ぐ
            html.append("<p class='attitude-prompt'><a class='attitude-link' href='").append(attitudeLink).append("'>♡♡♡なにか言いたがっています♡♡♡</a></p>"); // ★ リンククリック時だけコメント画面を開く
        }
        if (!eventRequest && total > 0 && rate == 100) {
            html.append("<p class='event-prompt'><a class='event-link' href='/attitude?event=1'>♡ 俺とTodo、どっちが大切なの？ ♡</a></p>"); // ★ 達成率100%時だけイベント専用リンクを表示する
        }
        if (showAttitude) { // ★ ボタン経由のときだけセリフを表示する
            html.append("<p>").append(randomAttitude(rate, attitudeType)).append("</p>"); // ★ 達成率に応じたセリフをランダム表示する
        }
        if (eventRequest && total > 0 && rate == 100) { // ★ イベント専用リンクを押したとき、達成率100%なら表示する
            html.append("<h2 class='event-question'>俺とTodo、どっちが大切なの？</h2>"); // ★ 特別イベントの質問を表示する
            html.append("<div class='event-options'>"); // ★ 選択肢を横並びボタンにする
            html.append("<a href='/event?choice=you'>あなた</a>"); // ★ 選択肢1を表示する
            html.append("<a href='/event?choice=todo'>Todo</a>"); // ★ 選択肢2を表示する
            html.append("<a href='/event?choice=work'>とりあえず働いて</a>"); // ★ 選択肢3を表示する
            html.append("</div>");
        }
        return html.append("</section></main></body></html>").toString(); // ★ 固定サマリー領域を閉じる
    }

    private static String randomAttitude(int rate, String attitudeType) { // ★ 達成率別に複数のセリフから選ぶ
        String[] lines;
        if ("done".equals(attitudeType)) {
            lines = new String[]{
                    "へえ、できたんだ。最初からそうすればよかったのに。",
                    "すごいね。普通の人は当たり前にやってることだけど。",
                    "俺が何も言わなかったら、どうせやってなかったでしょ？",
                    "頑張ったね。でも俺との時間はいつ作ってくれるの？",
                    "最近頑張ってるね。俺がいなくても平気になっちゃった？",
                    "やればできるじゃん。今まで何してたの？",
                    "そんなに頑張って、誰に褒めてもらいたいの？",
                    "今日だけじゃ意味ないからね。明日もちゃんとできる？",
                    "俺よりTodoの方が大事なんだ。へえ。",
                    "おめでとう。まあ、俺は最初からできると思ってたけど。"
            };
        } else if ("undone".equals(attitudeType)) {
            lines = new String[]{
                    "またできなかったんだ。期待した俺が馬鹿だったね。",
                    "できないなら最初からやるって言わなきゃいいのに。",
                    "忙しかった？ うん、みんな忙しいよね。",
                    "俺が厳しいんじゃなくて、君が甘いだけでしょ？",
                    "また明日やるの？ その明日っていつ来るの？",
                    "何回同じこと繰り返したら気が済むの？",
                    "俺がいないと本当に何もできないんだね。",
                    "謝らなくていいよ。どうせまた同じことするんでしょ？",
                    "別に責めてないよ。事実を言ってるだけじゃん。",
                    "そんな顔しないでよ。俺が悪者みたいじゃん。"
            };
        } else if (rate < 25) {
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
        String[] messages;
        if ("you".equals(choice)) {
            messages = new String[]{
                    "俺が大切なんだ？ でも今日のTodoは全部終わらせたんだよね。俺よりTodoを優先してた時間の方が長くない？",
                    "俺が一番なら、なんでTodoを100％達成するまで俺のこと放っておいたの？",
                    "全部終わったから俺のところに来たんでしょ？ 俺ってTodoの次なんだね。",
                    "俺を選んでくれたのは嬉しいけど、Todoを全部終わらせてから言われても説得力ないよね。",
                    "Todoは100％達成したんだ。じゃあ次は俺への愛情も100％にしてくれる？"
            };
        } else if ("todo".equals(choice)) {
            messages = new String[]{
                    "Todoを100％達成したうえに、俺よりTodoが大切って言うんだ。もう俺の立場ないじゃん。",
                    "全部終わらせたんだから、もうTodoに用事ないでしょ？ それでも俺より大切なの？",
                    "Todoを全部達成できたの、俺が見守ってたからじゃないの？ 感謝くらいしてよ。",
                    "へえ、100％達成したからって強気だね。俺がいなくても生きていけるって言いたいわけ？",
                    "俺よりTodoを選ぶんだ。100％達成した自分がそんなに偉いと思ってるの？"
            };
        } else {
            messages = new String[]{
                    "Todoを100％達成したからって、働いてない俺に説教する権利があると思ってるの？",
                    "君は今日のTodoを全部終わらせたんだろうけど、俺はまだ本気を出してないだけだから。",
                    "100％達成した途端に上から目線？ 俺だってやることがあれば全部できるし。",
                    "君はTodoを100％終わらせた。でも俺はまだ仕事を始めてない。それを比べるのは違くない？",
                    "Todoを全部終わらせたからって、俺にまで結果を求めないでくれる？ そういうプレッシャーが一番よくないんだよ。"
            };
        }
        String message = messages[(int) (Math.random() * messages.length)]; // ★ 選択肢ごとの5候補からランダム表示する
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;font-family:'Comic Sans MS','Segoe Print',cursive;color:#f4f7ff;background:radial-gradient(circle at 50% 35%,#30466f 0,#111a2e 55%,#050914 100%);}.todo-panel{position:relative;display:flex;flex-direction:column;box-sizing:border-box;width:min(70vw,1200px);min-height:70vh;padding:48px;border:1px solid #6d83ae;border-radius:24px;background:rgba(9,16,34,.78);box-shadow:0 20px 60px rgba(0,0,0,.45);display:flex;flex-direction:column;justify-content:center;align-items:center;}.attitude-message{font-size:1.5rem;line-height:1.8;max-width:90%;}.attitude-link{color:#ffd6f5;text-decoration:none;font-weight:bold;}.attitude-link:hover{color:#ffffff;text-decoration:underline;}</style><script>function saveTodoScroll(){var l=document.querySelector('.todo-list');if(l){sessionStorage.setItem('todoListScrollTop',l.scrollTop)}sessionStorage.setItem('pageScrollTop',window.scrollY)}window.addEventListener('load',function(){var l=document.querySelector('.todo-list');var t=sessionStorage.getItem('todoListScrollTop');var p=sessionStorage.getItem('pageScrollTop');if(l&&t!==null){l.scrollTop=Number(t)}if(p!==null){window.scrollTo(0,Number(p))}});document.addEventListener('click',function(e){if(e.target.closest('span.todo-actions a')){saveTodoScroll()}});document.addEventListener('submit',function(){saveTodoScroll()});</script></head><body><main class='todo-panel'>"
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

    private static void redirectAfterStatus(HttpExchange exchange, String type) throws IOException {
        exchange.getResponseHeaders().set("Location", "/?attitudeType=" + type);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void redirectAfterAdd(HttpExchange exchange) throws IOException { // ★ 追加直後だけリンクを隠す
        exchange.getResponseHeaders().set("Location", "/?added=1");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void redirectAfterDelete(HttpExchange exchange) throws IOException { // ★ 削除直後だけリンクを隠す
        exchange.getResponseHeaders().set("Location", "/?deleted=1");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
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
