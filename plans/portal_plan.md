# 資源ワールド ⇄ 島ポータル仕様書（設計・実装・運用まとめ）

このドキュメントは、Vanilife リポジトリ内で実装する「資源ワールドと各個人の島の間のポータル」仕様と実装指示を1つにまとめたものです。CLI で渡しやすい1ファイル形式にしています（運用/実装担当者がそのまま参照／コピーできる構成）。

---

## 目的（概要）
- 島側にあるポータルを使って資源ワールドへ行ける仕組みを実装する。
- 資源ワールド側では「出口ポータルブロックを生成しない」。プレイヤーは資源ワールドの「接続先座標」へ直接スポーンする。
- 資源ワールドの接続先：プレイヤーが資源ワールドで最後に使用したベッド位置を優先。未使用／破壊済みの場合は決定論的に算出した座標を使う。
- 算出座標は IslandPos と worldSeed を入力にし、ワイプ（seed 変更）ごとに一意となる。位置は原点(0,0)付近に密集させる。
- 「資源側から島に戻る」ための Escape メニューボタンは将来的にリソースパック（Dialog_tag）で実装可能だが、まずはサーバーコマンド/GUIを用意する。

---

## 要件（確定）
1. キャッシュは必須：計算したスポーン座標は永続化してキャッシュする（キー = worldId | seed | islandX | islandZ）。
2. ベッド位置は「プレイヤー単位」で保存する（lastBedLocation per player per world）。
3. 永続化は Exposed（既存コードに合わせる）を使う。Koin による DI で Repository を注入する。
4. DB：PostgreSQL を想定（Hikari + Exposed）。Schema は下記参照。
5. ResourceSpawn キャッシュは「メモリキャッシュ（ConcurrentHashMap） + Exposed 永続化」構成。compute-if-missing は key 毎の in-flight lock で1回だけ計算。
6. PortalFinder（既存）を plugin-islands に移し `net.azisaba.vanilife.islands.portal` 下で再利用する（枠検出ロジックはそのまま利用）。
7. 資源ワールド側では枠の検出は行っても良いが、ブロックを生成しない分岐を入れる。島側からの遷移時に spawn 解決を行い、チャンクロード→安全化→テレポートする。

---

## キャッシュ（キー・データモデル）
- キャッシュキー（文字列例）:
  - `<worldId>|<seed>|<islandX>|<islandZ>`
- CacheEntry（モデル）:
  - worldId: String
  - seed: Long
  - islandX: Int
  - islandZ: Int
  - spawnX: Int
  - spawnY: Int
  - spawnZ: Int
  - createdAt: timestamptz
  - lastUsedAt: timestamptz
  - version: Int
  - flags: JSONB / text (nullable)

動作:
- getOrCompute(islandPos, world, seed):
  - メモリキャッシュに存在すれば返す（and touch lastUsedAt）。
  - 無ければ key 毎 Mutex/Deferred で計算を1回だけ行う。
  - 計算後、永続化（upsert）してメモリに入れる。
- ワイプ（seed 変更）はキーに seed を含めるため自動で別キーとなる。古いキャッシュは管理コマンドで削除できる。

---

## DB（Exposed テーブル定義：参考）
（Exposed v1 API スタイルで表現）

```kotlin
object ResourceSpawnTable : Table("resource_spawn_cache") {
    val worldId = text("world_id")
    val seed = long("seed")
    val islandX = integer("island_x")
    val islandZ = integer("island_z")
    val spawnX = integer("spawn_x")
    val spawnY = integer("spawn_y")
    val spawnZ = integer("spawn_z")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val lastUsedAt = datetime("last_used_at").defaultExpression(CurrentDateTime)
    val version = integer("version").default(1)
    val flags = text("flags").nullable()
    override val primaryKey = PrimaryKey(worldId, seed, islandX, islandZ, name = "pk_resource_spawn")
}
```

Flyway での SQL（参考）:
```sql
CREATE TABLE IF NOT EXISTS resource_spawn_cache (
  world_id TEXT NOT NULL,
  seed BIGINT NOT NULL,
  island_x INT NOT NULL,
  island_z INT NOT NULL,
  spawn_x INT NOT NULL,
  spawn_y INT NOT NULL,
  spawn_z INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version INT NOT NULL DEFAULT 1,
  flags JSONB,
  PRIMARY KEY (world_id, seed, island_x, island_z)
);
CREATE INDEX IF NOT EXISTS idx_resource_spawn_cache_last_used ON resource_spawn_cache (last_used_at);
```

---

## Exposed ベース Repository API（インターフェース）
- suspend fun find(worldId: String, seed: Long, islandX: Int, islandZ: Int): CacheEntryModel?
- suspend fun upsert(entry: CacheEntryModel): CacheEntryModel
- suspend fun touchLastUsed(worldId: String, seed: Long, islandX: Int, islandZ: Int)
- suspend fun deleteBySeed(worldId: String, seed: Long)
- suspend fun delete(worldId: String, seed: Long, islandX: Int, islandZ: Int)

実装は `suspendTransaction(database) { ... }` を使う（既存コードに合わせる）。

---

## Koin（DI）統合
- Koin module 提供例（plugin-islands 側の Database を注入）:

```kotlin
val PortalDatabaseModule = module {
  single<ResourceSpawnRepository> { ExposedResourceSpawnRepository(get()) } // get() -> org.jetbrains.exposed.v1.jdbc.Database
}
```

- プラグイン起動時に Koin を起動済みなら module を load する。Database 作成は既存 islands.Database.setupDatabase を再利用する想定。

---

## ポータル検出と遷移フロー（イベント）
1. PortalFinder（既存）で枠を検出（IgniteListener などがトリガ）。
2. 判定:
   - 島側の枠 → 「島→資源」の操作:
     - ResourceSpawnCache.getOrCompute(islandPos, resourceWorld, seed) を呼ぶ。
     - 返却 Location に対してチャンクロード→安全化チェック→プレイヤーを teleportAsync。
     - 資源ワールド側で NetherPortal ブロックは作らない（ResourcePortals.createWithAnimation を呼ばない or 生成分岐）。
   - 資源側の枠 → 「資源→島」の操作:
     - まずプレイヤーの lastBedLocation（プレイヤー単位）を参照。存在かつ安全ならそこへテレポート。
     - 存在しない／破壊済みなら ResourceSpawnCache（islandPos-based）を参照して島へテレポート（※ここは逆向きの扱い。要仕様調整）。
3. ベッド記録:
   - PlayerBedEnterEvent / PlayerRespawnEvent を監視し、資源ワールドでベッドを使った場合は `LastBedStorage.setLastBed(worldName, playerUuid, bedLocation)` を更新する。

---

## 算出アルゴリズム（未キャッシュ時の決定論的算出）
- 入力: islandX, islandZ, worldSeed (Long)
- 簡易ハッシュ: h = f(islandX, islandZ, worldSeed)（例: 64bit 簡易混合）
- rnd = Random(h)
- radius = BASE_RADIUS + rnd.nextDouble() * RADIUS_VARIANCE
  - デフォルト案: BASE_RADIUS = 8, RADIUS_VARIANCE = 56 （→ 8..64）
  - 密集度を上げたい場合は BASE_RADIUS を小さく、VARIANCE を小さくする
- angle = rnd.nextDouble() * 2π
- x = round(cos(angle) * radius), z = round(sin(angle) * radius)
- y = world.getHighestBlockYAt(x, z) + 1 （ただし安全でない場合は周辺探索）
- 返却 Location は (x + 0.5, y, z + 0.5)

安全化:
- チャンクを確実にロードしてから `getHighestBlockYAt` を実行する（folia の regionDispatcher 等を利用）。
- 溶岩/液体/穴等がある場合は ±N 範囲で代替候補を探索。
- 必要なら着地後の短時間無敵/保護ルールを導入。

---

## キャッシュのライフサイクルと運用コマンド
- 自動無効化: seed が変われば自動的に新しいキーになる（ワイプ対応）。
- 管理コマンド（提案）:
  - `/resourcecache show <world> <seed> <islandX> <islandZ>`
  - `/resourcecache invalidate <world> <seed> [islandX islandZ]`
  - `/resourcecache rebuild <world> <seed> [islandX islandZ]` — 再計算して上書き
- 定期メンテ:
  - 古いキャッシュの cleanup（lastUsedAt ベースで TTL を設定して削除）
  - バックアップ（DB dump / JSON export）

---

## Escape メニュー（Dialog_tag）について
- サーバーだけでは ESC メニューを改変できないため選択肢:
  - A) リソースパックで dialog tag を追加し、ボタンに `run_command` を設定して `/island return` を呼ばせる（UX良）。クライアントのリソースパック導入が必須。
  - B) まずはサーバー側コマンド `/island return` / GUI `/island menu` を提供（導入簡単）。将来的にリソースパック版を追加。
- 推奨: まず B（サーバーコマンド/GUI）で素早く導入し、その後 A を検討。

---

## 既存コードとの統合指示（移行手順）
1. plugin-portal のコードを plugin-islands にコピーしてパッケージを `net.azisaba.vanilife.islands.portal` にする。
2. build.gradle.kts を更新（必要な依存は既に plugin-islands に入っていることを確認）。
3. plugin-islands の Main に portal の初期化（イベント登録・Koin module load）を追加。
4. Database.setupTables() に `ResourceSpawnTable` を追加（SchemaUtils.create）。
5. PortalFinder をそのまま利用し、IgniteListener 等を plugin-islands に移行。
6. ResourceSpawnCache を ExposedRepository に接続し、getOrCompute を実装。
7. テスト：複数プレイヤー、ワイプ後の挙動、ベッド利用、unsafe地形の再計算などを確認。
8. plugin-portal モジュールは削除または archive。

---

## 環境変数 / 設定キー（例）
- VANILIFE_DB_URL (例: jdbc:postgresql://host:5432/vanilife)
- VANILIFE_DB_USER
- VANILIFE_DB_PASSWORD
- VANILIFE_DB_MAX_POOL (デフォルト: 8)
- VANILIFE_BASE_RADIUS (デフォルト: 8)
- VANILIFE_RADIUS_VARIANCE (デフォルト: 56)

---

## 実装注意点（チェックリスト）
- [ ] compute-if-missing は key 毎に単一実行（Mutex/Deferred）
- [ ] Exposed の suspendTransaction を使用（既存スタイルに合わせる）
- [ ] DB アクセスは Dispatchers.IO でラップ（必要に応じて）
- [ ] チャンクロードと安全化は非同期に処理し、テレポートは成功確認を行う
- [ ] キャッシュ upsert はトランザクション内で確実に保存
- [ ] 管理コマンドは権限制御を行う（OP または管理用パーミッション）
- [ ] テスト（単体＆統合）：Testcontainers で Postgres を使うと良い

---

## 既存ライブラリとのマッチング
- Exposed（既に `plugins/plugin-islands` で利用中）を採用。
- HikariCP は既存 Database 作成コードで使われているため再利用。
- Koin は既に依存に含まれている（DI で repository を注入）。
- folia / mccoroutine の regionDispatcher を利用してチャンクの安全なロードを行う。

---

## 優先タスク（実装順）
1. Exposed テーブル & Repository の実装
2. ResourceSpawnCache（メモリ + リポジトリ接続）
3. BedTracker（PlayerBedEnterEvent / PlayerRespawnEvent）
4. PortalFinder の移動（plugin-islands に統合）
5. ResourceTeleporter の実装（getOrCompute → チャンクロード → 安全化 → teleport）
6. 管理コマンドの追加
7. テスト（ローカル/CI）
8. （任意）リソースパック Dialog_tag の実装

---

## QA / テストケース（必須）
- 初回アクセス：計算→永続化→再起動後も同じ位置を返す
- 同一島で複数ポータルからの同時アクセス：同一キャッシュ位置が使われる／重複計算が起きない
- ワイプ（seed 変更）で新しい位置が生成される
- ベッド利用が優先される／ベッド破壊時は再計算される
- 位置が unsafe の場合、代替探索→キャッシュ上書きされる
- 大量アクセスでの DB 負荷（接続プール設定確認）

---

## 管理者向け運用メモ（短く）
- DB マイグレーションは起動時に Flyway 実行か、SchemaUtils.create を利用
- DB 機密情報は環境変数で管理
- 位置が密集するため spawn 保護（無敵時間や専用保護領域）を導入することを推奨
- 古いキャッシュは TTL ポリシーで cleanup（例: lastUsedAt が 180 日以上なら削除）

---

## 参考：実装スニペット（要点のみ）
- computeDeterministicPosition (pseudo-Kotlin):

```kotlin
fun computeDeterministicPosition(islandX: Int, islandZ: Int, worldSeed: Long): Location {
  var h = islandX.toLong() * 0x9E3779B97F4A7C15L xor (islandZ.toLong() * 0xC2B2AE3D27D4EB4FL) xor worldSeed
  if (h == 0L) h = 0xdeadbeefL
  val rnd = Random(h)
  val radius = BASE_RADIUS + rnd.nextDouble() * RADIUS_VARIANCE
  val angle = rnd.nextDouble() * 2.0 * PI
  val x = (cos(angle) * radius).roundToInt()
  val z = (sin(angle) * radius).roundToInt()
  val y = world.getHighestBlockYAt(x, z) + 1
  return Location(world, x + 0.5, y.toDouble(), z + 0.5)
}
```

---

## 次のアクション（選択肢）
- A: これを元に plugin-islands に実実装し PR を作成する（ブランチ名を指示してください）。
- B: まず Exposed テーブル + Repository の差分だけ作成してレビューする。
- C: まずは BedTracker + `/island return` コマンド（サーバー側 UI）を早期導入する。

実装 PR を作る場合に必要な情報:
- ターゲットブランチ名
- DB 接続情報は不要（環境変数名だけ合わせます）。CI 用に Testcontainers 設定を追加希望なら指示ください。

---

作業履歴や今後の変更点はこのファイルを更新して逐次追記してください。必要ならこの Markdown をそのままコミット／PR 用説明文としても使えます。