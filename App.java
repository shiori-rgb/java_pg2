import com.sun.net.httpserver.HttpServer; // 簡単なHTTPサーバー
import java.net.InetSocketAddress; // 通信先の住所とポート番号
import java.net.URLDecoder; // URLの文字を元に戻す機能
import java.nio.charset.StandardCharsets; // 文字コードを指定する機能
import java.util.List; // 文字列の一覧を扱う型
import java.util.ArrayList; // 文字列の一覧を作る型

public class App { // Appというクラス
    static List<Todo> todos = new ArrayList<>(); // ★変更 Todoを保存する一覧
    static int nextId = 1; // ★変更 次に振る番号

    public static void main(String[] args) throws Exception { // プログラムの開始地点
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0); // 8080番ポートでサーバーを用意
        todos.add(new Todo(nextId++, "牛乳を買う")); // ★変更 起動時のサンプルTodo
        Todo egg = new Todo(nextId++, "卵を買う"); // ★変更 起動時のサンプルTodo
        egg.setDone(true); // ★変更 卵を買うを完了にする
        todos.add(egg); // ★変更 サンプルTodoを一覧に追加

        server.createContext("/", exchange -> { // "/"へのアクセスを処理
            String path = exchange.getRequestURI().getPath(); // アクセスされたパスを取り出す
            String method = exchange.getRequestMethod(); // GETかPOSTかを取り出す
            String message; // 返す文字を入れる変数
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // 通常の文字として返す
            if (path.equals("/add") && method.equals("POST")) { // POSTで/addに来たか比べる
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); // 送られた中身をUTF-8で受け取る
                String value = body.substring(5); // todo=の後ろを切り出す
                String title = URLDecoder.decode(value, StandardCharsets.UTF_8); // ★変更 日本語を元に戻してtitleに入れる
                if (!title.isEmpty()) { // ★変更 入力が空でないか比べる
                    todos.add(new Todo(nextId, title)); // ★変更 Todoを一覧に追加する
                    nextId++; // ★変更 次の番号に進める
                }
                exchange.getResponseHeaders().set("Location", "/"); // 戻り先を指定する
                exchange.sendResponseHeaders(303, -1); // ルートへ移動する応答を返す
                exchange.close(); // 通信を閉じる
                return; // この分岐を抜ける
            } else if (path.equals("/done") && method.equals("GET")) { // ★追加 完了処理
                String query = exchange.getRequestURI().getQuery(); // ★追加 クエリを受け取る
                if (query != null && query.startsWith("id=") && query.length() > 3) { // ★追加 idがあるか確認する
                    try { // ★追加 idを数値に変換する
                        int id = Integer.parseInt(query.substring(3)); // ★追加 idを数値にする
                        for (Todo todo : todos) { // ★追加 Todoを1件ずつ見る
                            if (todo.getId() == id) { // ★追加 idが一致したか確認する
                                todo.setDone(true); // ★追加 Todoを完了にする
                                break; // ★追加 1件見つかったので抜ける
                            }
                        }
                    } catch (NumberFormatException e) { // ★追加 数字でないidは何もしない
                    }
                }
                exchange.getResponseHeaders().set("Location", "/"); // ★追加 一覧へ戻る
                exchange.sendResponseHeaders(303, -1); // ★追加 一覧へ移動する応答を返す
                exchange.close(); // ★追加 通信を閉じる
                return; // ★追加 この分岐を抜ける
            } else if (path.equals("/delete") && method.equals("GET")) { // ★追加 削除処理
                String query = exchange.getRequestURI().getQuery(); // ★追加 クエリを受け取る
                if (query != null && query.startsWith("id=") && query.length() > 3) { // ★追加 idがあるか確認する
                    try { // ★追加 idを数値に変換する
                        int id = Integer.parseInt(query.substring(3)); // ★追加 idを数値にする
                        todos.removeIf(todo -> todo.getId() == id); // ★追加 idが一致するTodoを削除する
                    } catch (NumberFormatException e) { // ★追加 数字でないidは何もしない
                    }
                }
                exchange.getResponseHeaders().set("Location", "/"); // ★追加 一覧へ戻る
                exchange.sendResponseHeaders(303, -1); // ★追加 一覧へ移動する応答を返す
                exchange.close(); // ★追加 通信を閉じる
                return; // ★追加 この分岐を抜ける
            } else if (path.equals("/")) { // パスが/か比べる
                String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>body { max-width: 600px; margin: 20px auto; padding: 0 16px; font-size: 16px; }</style></head><body><h1>わたしのTodo</h1>"; // 変更：見出しと最小限のスタイルを追加
                html += "<form method='post' action='/add'>"; // 入力フォームを始める
                html += "<input name='todo'><button>追加</button></form>"; // 入力欄と追加ボタンを作る
                if (todos.isEmpty()) { // 変更：一覧が空か確認
                    html += "<p>やることは、いまゼロです</p>"; // 変更：空のときの表示を追加
                } else { // 変更：Todoがあるときの表示
                    html += "<ul>"; // Todo一覧を始める
                    for (Todo todo : todos) { // ★変更 Todoを1件ずつ取り出す
                        String mark = ""; // ★変更 完了印を入れる変数
                        if (todo.isDone()) { // ★変更 Todoが完了しているか比べる
                            mark = " ✔"; // ★変更 完了印を付ける
                        }
                        html += "<li>" + todo.getTitle() + mark + " <a href='/done?id=" + todo.getId()
                                + "'>完了</a> <a href='/delete?id=" + todo.getId() + "'>削除</a></li>"; // ★追加
                                                                                                    // Todoと完了・削除リンクをHTMLに追加する
                    }
                    html += "</ul>"; // Todo一覧を閉じる
                }
                html += "</body></html>"; // 変更：HTMLを閉じる
                message = html; // 組み立てたHTMLを返す
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); // HTMLとして返す
            } else { // ★変更 該当しないパスの受け皿
                message = "ページが見つかりません"; // ★変更 該当しないパスの受け皿
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

class Todo { // ★変更 Todoを表すクラス
    private final int id; // ★変更 Todoの番号
    private final String title; // ★変更 やること
    private boolean done; // ★変更 完了したかどうか

    Todo(int id, String title) { // ★変更 Todoを作るコンストラクタ
        this.id = id; // ★変更 番号を保存
        this.title = title; // ★変更 やることを保存
        this.done = false; // ★変更 最初は未完了にする
    }

    int getId() { // ★変更 番号を読み出すメソッド
        return id;
    }

    String getTitle() { // ★変更 やることを読み出すメソッド
        return title;
    }

    boolean isDone() { // ★変更 完了状態を読み出すメソッド
        return done;
    }

    void setDone(boolean done) { // ★変更 完了状態を書き換えるメソッド
        this.done = done;
    }
}
