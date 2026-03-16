# 資源ポータル仕様

## 概要
このドキュメントは Vanilife に実装されている「島 ⇄ 資源ワールド」のポータル挙動をまとめたものです。
点火によるポータル検出・生成（演出）と、テレポート（島→資源 / 資源→島）の流れ、使われるブロックや設定、開発者向けの実装詳細を含みます。

---

## プレイヤー向け（短く、実用中心）

### 何ができるか
- 島側にある指定の枠を点火すると、資源ワールドへ移動できる（逆に資源ワールド側で点火すると島へ戻る仕組み）。
- 資源ワールドから帰るときは、資源ワールドで最後に使ったベッド位置に優先して戻る（ベッドが無い or 破壊されている場合は島のスポーンへ）。

### 使い方（簡単手順）
1. ポータル枠を作る（フレーム材は Prismarine）。
2. 内部に火を付ける（火打石や火打ち道具で点火）。
3. 点火後、演出（パーティクル／音）が発生し、テレポートが起きるか、ポータルの見た目が生成される。

### 作り方（詳細）
- フレームに使うブロック: Prismarine（`Material.PRISMARINE`）。
- 内部サイズの目安：内幅（横） 2〜21、内高さ（縦） 3〜21 の枠を作る。
- 内側は空気（`AIR`）か火（`FIRE`）である必要がある（内部に他ブロックがあると検出されません）。

### テレポート時の挙動
- 島で点火した場合：島プレイヤー → 資源ワールドの決定地点（または安全な近傍）へ移動。
- 資源ワールドで点火した場合：最後に使ったベッド位置（優先）へ帰還。無ければ島スポーンへ。
- 着地は「安全チェック」後に行われ、溶岩やマグマ、液体、不安定な地面は避けられます。
- ポータルが成功しないときはプレイヤーにエラーメッセージが出ます（例: "Could not connect to resource world." / "Could not return to your island."）。

### 見た目・演出
- 内部に `NETHER_PORTAL` ブロックを埋める演出が入り、パーティクル（爆発系）と音（RESPAWN_ANCHOR_CHARGE / BEACON_AMBIENT）が鳴ります。
- `plugin-islands` 環境では資源ワールド側にポータルブロックを作らず、直接テレポートする運用が採られることがあります。

### 注意点（プレイヤー向け）
- フレームは必ず Prismarine を使ってください（他ブロックだと検出されません）。
- 資源ワールドでのベッドが優先されます。ベッドが壊れたら復帰先が変わる可能性があります。
- 管理者専用の管理コマンド（キャッシュ参照・無効化等）は一般プレイヤーは使えません。

---

## 開発者向け（実装・内部仕様）

### 主要な設計要点
- 検出 →（必要なら）ポータル生成（演出）→ テレポート（安全化）→ 永続化 の順序で処理。
- Folia 対応：ブロックアクセス・チャンクロード・テレポートは `regionDispatcher` / `getChunkAtAsync` / `teleportAsync` を用いて行う。
- キャッシュ：島ごとの資源スポーン座標はメモリ + DB（Exposed）で保持。並列での重複計算は `inFlight`（Deferred）で抑止。

### 検出（`PortalFinder` の振る舞い）
- 検出開始位置は点火されたブロックの下（`event.block.getRelative(BlockFace.DOWN)`）。
- フレーム判定は `framePredicate`（実装中は `blockState.type == FRAME_BLOCK`）で行う。現在 `FRAME_BLOCK` は `Material.PRISMARINE`。
- 許容サイズ: 内幅 2..21、内高さ 3..21。`measureWidth` / `measureHeight` で枠サイズを計測し、`validateCandidate` で周囲フレーム・内部が空気/火であることを確認。
- `getBlockStateAt` はチャンクオーナー判定を行い、必要に応じて `regionDispatcher` でブロック状態を取得する。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/finder/PortalFinder.kt`

### ポータル生成（演出）
- `ResourcePortals.createWithAnimation` は検出領域の内側を層ごと（上から下）に `Material.NETHER_PORTAL` の BlockData（`Orientable`）に置き換える（Axis を X または Z に設定）。各層ごとに短い遅延（50ms）を入れてアニメを行う。
- 演出後、中心にパーティクルと複数回のサウンドを鳴らす。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourcePortals.kt`

### イベントトリガ（点火）
- 点火イベント: `BlockIgniteEvent` をリスン（`PortalIgniteListener` / `IgniteListener`）。検出成功時に演出 or テレポート処理が走る。
- `PortalIgniteListener`（islands）は、点火されたワールドが資源ワールドかを判定し、資源ワールドなら `teleportResourceToIsland(player)` を、それ以外なら `teleportIslandToResource(player, islandPos)` を呼ぶ。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/listener/PortalIgniteListener.kt`

### テレポート処理（`ResourceTeleporter`）
- 島→資源（`teleportIslandToResource`）:
  - `ResourceSpawnCache.getOrCompute(islandPos, world)` でキャッシュを取得／計算。
  - `findSafeLocation` で安全地を探す（見つからなければ決定位置に 0.5 オフセットで補正）。
  - 安全位置がキャッシュと異なる場合は `cache.overwrite` で更新。
  - `regionDispatcher` 上で `world.getChunkAtAsync(..., true).await()` でチャンクを確保し、`player.teleportAsync(safe).await()` で非同期テレポート。
- 資源→島（`teleportResourceToIsland`）:
  - `LastBedStorage.getLastBed(resourceWorldId, uuid)` を優先して戻す（`findSafeLocation` で再検証）。
  - 存在しない/unsafe なら `LastBedStorage.clear` して島スポーンへフォールバック。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceTeleporter.kt`

### スポーン座標キャッシュ（`ResourceSpawnCache`）
- メモリキャッシュ: `memoryCache: ConcurrentHashMap<CacheKey, ResourceSpawnCacheEntry>`
- in-flight 保護: `inFlight: ConcurrentHashMap<CacheKey, Deferred<ResourceSpawnCacheEntry>>`（同一キーでの並列計算を防止）
- 取得手順（`getOrCompute`）:
  1. メモリキャッシュ查定。あれば touch（lastUsed 更新）して返却。
  2. DB（`ResourceSpawnRepository.find`）検索。あればメモリに入れて返却。
  3. 無ければ `inFlight.computeIfAbsent` で Deferred を作り `computeEntry` を実行。計算結果を DB に upsert して返す。
- `computeEntry` の流れ: `computeDeterministicXZ` → `highestY` → `findSafeLocation` → `ResourceSpawnCacheEntry` を構築。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceSpawnCache.kt`

#### 決定論的座標アルゴリズム（要点）
- 入力: `islandX`, `islandZ`, `worldSeed`
- 内部で混合ハッシュ `h` を作成 → `Random(h)` を生成 → `radius = baseRadius + rnd.nextDouble()*radiusVariance` → `angle = rnd.nextDouble()*(2π)` → `x = round(cos(angle)*radius)`, `z = round(sin(angle)*radius)`
- このため、同一 islandPos と seed の組合せでは常に同じ座標が返る（ワイプ: seed 変更で新しい座標）。

抜粋実装（参考）:
```kotlin
private fun computeDeterministicXZ(islandX: Int, islandZ: Int, worldSeed: Long): Pair<Int, Int> {
  var h = islandX.toLong() * 0x9E3779B97F4A7C15UL.toLong() xor (islandZ.toLong() * 0xC2B2AE3D27D4EB4FUL.toLong()) xor worldSeed
  if (h == 0L) h = 0xDEADBEEFL
  val random = Random(h)
  val radius = config.baseRadius + random.nextDouble() * config.radiusVariance
  val angle = random.nextDouble() * 2.0 * PI
  val x = (cos(angle) * radius).roundToInt()
  val z = (sin(angle) * radius).roundToInt()
  return x to z
}
```

### 安全地探索（`findSafeLocation` / `isSafe`）
- 探索は中心から半径 `0..safeSearchRadius` を走査（dx, dz）し、各候補でチャンクを読み込み `getHighestBlockYAt` の結果＋1 を候補Yとして `isSafe` を評価。
- `isSafe` の条件:
  - 足（y）と頭（y+1）が空（空気）である。
  - 下（y-1）が `isSolid == true`。
  - 下ブロックが `Material.LAVA` または `Material.MAGMA_BLOCK` でないこと。
  - 下ブロックが液体でないこと。

### 永続化（Exposed Repository）
- `ResourceSpawnRepository`（Exposed 実装）: `find`, `upsert`, `touchLastUsed`, `deleteBySeed`, `delete` を提供。テーブルは `resource_spawn_cache`。
- `LastBedRepository`（Exposed 実装）: プレイヤーの資源ワールド上でのベッド位置を `resource_last_bed` テーブルに保持。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceSpawnRepository.kt`
参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/LastBedRepository.kt`

テーブル（要約）
```sql
-- resource_spawn_cache (主キー: world_id, seed, island_x, island_z)
world_id TEXT, seed BIGINT, island_x INT, island_z INT, spawn_x INT, spawn_y INT, spawn_z INT, created_at TIMESTAMPTZ, last_used_at TIMESTAMPTZ, version INT, flags JSONB

-- resource_last_bed (主キー: world_id, player_uuid)
world_id TEXT, player_uuid UUID, x INT, y INT, z INT, yaw FLOAT, pitch FLOAT, updated_at BIGINT
```

### 設定項目（`Config.kt`）
- `portal.resourceWorld` = `"resources"`（資源ワールド名、デフォルト）
- `portal.baseRadius` = `8`
- `portal.radiusVariance` = `56`
- `portal.safeSearchRadius` = `8`

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/Config.kt`

### 管理コマンド
- `resourcecache` コマンド（管理者向け）:
  - `resourcecache show <world> <seed> <islandX> <islandZ>`
  - `resourcecache invalidate <world> <seed> [islandX islandZ]`
  - `resourcecache rebuild <world> <seed> <islandX> <islandZ>`
- 実装では実行に `vanilife.resourcecache.admin` の権限または OP が必要。

参照: `plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/commands/ResourceCacheCommand.kt`

### Folia / 非同期注意点
- ブロックアクセス・チャンク読み込み・テレポートは `plugin.regionDispatcher(location)` を使い、`getChunkAtAsync(..., true).await()` と `player.teleportAsync(...).await()` で確実に非同期/region 安全に処理している。Folia を使う環境ではこの点が必須。

### テストと検証項目（推奨）
- `computeDeterministicXZ` の決定性（同一 seed/island 組で常に同じ座標）。
- `findSafeLocation` が各種地形（海、溶岩湖、洞窟上、密集地）で正しく安全地を返すか。
- `PortalFinder` が縦向き/横向きの枠を正しく検出するか。
- 同時アクセス時の `inFlight` 動作（重複計算防止）と DB upsert の整合性。
- テレポート往復でプレイヤーが無限ループしないか（必要ならクールダウンを導入）。

### 改善案 / 注意点（将来的に検討）
- テレポート後の「短時間の判定無効化 / クールダウン」を入れ、A↔B のループを防ぐ。
- 資源ワールド側はポータルブロックを作らず完全にテレポートに切り替える（現在はモードにより分岐可）。
- 大量アクセスに対する DB 負荷対策（TTL による古いキャッシュ削除など）。
- カスタムイベント（`PortalEnterEvent` 等）を投げて他プラグインが介入できるようにする。

### 参照実装ファイル
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourcePortals.kt`
`plugins/plugin-portal/src/main/kotlin/net/azisaba/vanilife/portal/ResourcePortals.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/finder/PortalFinder.kt`
`plugins/plugin-portal/src/main/kotlin/net/azisaba/vanilife/portal/finder/PortalFinder.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/finder/DetectedPortal.kt`
`plugins/plugin-portal/src/main/kotlin/net/azisaba/vanilife/portal/finder/DetectedPortal.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/listener/PortalIgniteListener.kt`
`plugins/plugin-portal/src/main/kotlin/net/azisaba/vanilife/portal/listener/IgniteListener.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceSpawnCache.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceSpawnRepository.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/ResourceTeleporter.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/LastBedStorage.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/LastBedRepository.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/portal/commands/ResourceCacheCommand.kt`
`plugins/plugin-islands/src/main/kotlin/net/azisaba/vanilife/islands/Config.kt`
`plans/portal_plan.md`

---

## FAQ（短く）
**Q: フレームは何でも良い？**
**A:** いいえ。実装では `PRISMARINE`（`Material.PRISMARINE`）がフレーム材として設定されています。変更可ですがコード修正が必要です。

**Q: ポータルは常にブロックを作る？**
**A:** `plugin-portal` の演出では `NETHER_PORTAL` ブロックを作るが、`plugin-islands` の運用では資源ワールド側にブロックを作らず直接テレポートする運用も可能です（既存コードはモジュール分岐で扱える）。

---

## 管理者・開発者の次アクション案
- サーバー側で動作確認（島側で Prismarine 枠を作り点火 → 資源ワールドへ移動するか）
- DB に `resource_spawn_cache` / `resource_last_bed` テーブルを用意して動作確認
- テスト：`computeDeterministicXZ` / `findSafeLocation` のユニットテスト作成

---

作成しました: `docs/portal.md`。

確認・修正したい点があれば指摘してください。ファイルを別名で出力したり、英語版を追加することもできます。
