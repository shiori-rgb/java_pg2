import com.sun.net.httpserver.HttpServer; // 簡単なHTTPサーバー
import java.net.InetSocketAddress; // 通信先の住所とポート番号

public class App { // Appというクラス
    public static void main(String[] args) throws Exception { // プログラムの開始地点
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0); // 8080番ポートでサーバーを用意
        server.createContext("/", exchange -> { // "/"へのアクセスを処理
            String path = exchange.getRequestURI().getPath(); // アクセスされたパスを取り出す
            String message; // 返す文字を入れる変数
            if (path.equals("/hello")) { // パスが/helloか比べる
                String query = exchange.getRequestURI().getRawQuery(); // URLのクエリを取り出す
                String name = query.substring(5); // name=の後ろを切り出す
                message = "こんにちは、" + name + "さん！"; // 名前を応答に混ぜる
            } else if (path.equals("/bye")) { // パスが/byeか比べる
                message = "さようなら！";
            } else {
                message = "ページが見つかりません";
            }
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // UTF-8の文字として返す
            byte[] body = message.getBytes("UTF-8"); // 返す文字をバイト列に変換
            exchange.sendResponseHeaders(200, body.length); // 正常に返すことを通知
            exchange.getResponseBody().write(body); // 文字を送り返す
            exchange.getResponseBody().close(); // 通信を閉じる
        }); // "/"の処理を終了
        server.start(); // サーバーを起動
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）"); // 起動メッセージを表示
    } // mainメソッドを終了
} // Appクラスを終了
