import com.sun.net.httpserver.HttpServer; // 簡単なHTTPサーバー
import java.net.InetSocketAddress; // 通信先の住所とポート番号
import java.net.URLDecoder; // URLの文字を元に戻す機能
import java.nio.charset.StandardCharsets; // 文字コードを指定する機能
import java.util.List; // 文字列の一覧を扱う型
import java.util.ArrayList; // 文字列の一覧を作る型

public class App { // Appというクラス
    public static void main(String[] args) throws Exception { // プログラムの開始地点
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0); // 8080番ポートでサーバーを用意
        List<String> todos = new ArrayList<>(); // Todoを保存する空の一覧を一度だけ用意
        server.createContext("/", exchange -> { // "/"へのアクセスを処理
            String path = exchange.getRequestURI().getPath(); // アクセスされたパスを取り出す
            String method = exchange.getRequestMethod(); // GETかPOSTかを取り出す
            String message; // 返す文字を入れる変数
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // 通常の文字として返す
            if (path.equals("/add") && method.equals("POST")) { // POSTで/addに来たか比べる
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); // 送られた中身をUTF-8で受け取る
                String value = body.substring(5); // todo=の後ろを切り出す
                String todo = URLDecoder.decode(value, StandardCharsets.UTF_8); // 日本語を元に戻す
                if (!todo.isEmpty()) { // 入力が空でないか比べる
                    todos.add(todo); // Todoを一覧に追加する
                }
                exchange.getResponseHeaders().set("Location", "/"); // 戻り先を指定する
                exchange.sendResponseHeaders(303, -1); // ルートへ移動する応答を返す
                exchange.close(); // 通信を閉じる
                return; // この分岐を抜ける
            } else if (path.equals("/")) { // パスが/か比べる
                String html = "<form method='post' action='/add'>"; // 入力フォームを始める
                html += "<input name='todo'><button>追加</button></form>"; // 入力欄と追加ボタンを作る
                html += "<ul>"; // Todo一覧を始める
                for (String todo : todos) { // Todoを1件ずつ取り出す
                    html += "<li>" + todo + "</li>"; // TodoをHTMLに追加する
                }
                html += "</ul>"; // Todo一覧を閉じる
                message = html; // 組み立てたHTMLを返す
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); // HTMLとして返す
            } else if (path.equals("/hello")) { // パスが/helloか比べる
                String query = exchange.getRequestURI().getRawQuery(); // URLのクエリを取り出す
                String name = query.substring(5); // name=の後ろを切り出す
                message = "こんにちは、" + name + "さん！"; // 名前を応答に混ぜる
            } else if (path.equals("/bye")) { // パスが/byeか比べる
                message = "さようなら！";
            } else {
                message = "ページが見つかりません";
            }
            byte[] body = message.getBytes("UTF-8"); // 返す文字をバイト列に変換
            exchange.sendResponseHeaders(200, body.length); // 正常に返すことを通知
            exchange.getResponseBody().write(body); // 文字を送り返す
            exchange.getResponseBody().close(); // 通信を閉じる
        }); // "/"の処理を終了
        server.start(); // サーバーを起動
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）"); // 起動メッセージを表示
    } // mainメソッドを終了
} // Appクラスを終了
