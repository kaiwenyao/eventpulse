# EventPulse Outbox 简化修改计划

> 状态：待审核。目前只写计划，不修改业务代码。
> 日期：2026-09-01

## 1. 我们现在要解决什么问题

目前 `OutboxRelay` 的做法是：

```java
kafkaTemplate.send(
    event.getTopic(),
    event.getDedupKey(),
    event.getPayload()
);

event.setPublishedAt(Instant.now());
```

问题出在 `send()` 是异步的。

调用 `send()`，只表示“已经开始发送”，不表示 Kafka 已经确认收到。但是代码马上填写了 `published_at`，相当于提前宣布“发送成功”。

最坏的情况是：

```text
开始发送 Kafka
    ↓
Outbox 马上被标记为已发送
    ↓
Kafka 随后发送失败
    ↓
Relay 不会再处理这条消息
    ↓
消息永久丢失
```

这次修改的核心只有一句话：

> 必须等 Kafka 明确确认成功以后，才能填写 `published_at`。

在审核中又发现两个要一起补上的问题：

1. 一条永远发送失败的坏消息，不能把整个 Outbox 队列堵住。
2. Kafka 已经成功后，数据库要用一条简单的条件 UPDATE 做标记；不能因为 `findById(...).orElseThrow()` 又制造一个新的失败点。

## 2. 准备怎么改

修改后的流程：

```text
找到 published_at 和 failed_at 都为空的 Outbox 消息
    ↓
发送 Kafka
    ↓
等待 Kafka 的结果
    ├── 成功：填写 published_at
    ├── 失败，但还有救：记一次失败，本轮到此结束，下一轮重来
    └── 失败，且没救了：隔离这一条，本轮继续处理后面的消息
```

我们继续使用 `published_at` 判断是否发送成功，同时再补一个 `failed_at`，专门表示“这条消息本身有问题，系统先把它放到一边”：

```text
published_at = NULL，failed_at = NULL    还没发送成功，Relay 以后还会再试
published_at 有时间值                    Kafka 已经确认成功
failed_at 有时间值                       已隔离，等人工检查后再决定是否重发
```

`outbox` 表增加三列，用来记录“发送失败了几次”“上次错在哪”“什么时候被隔离的”：

```sql
publish_attempts INT          NOT NULL DEFAULT 0
last_error       VARCHAR(1000)
failed_at        TIMESTAMPTZ
```

这不是复杂的消息状态机，只是一个计数器、一段错误说明和一个隔离时间。加这三列的原因见第 3 节第三步：没有它们，一条永远发不出去的消息会把后面所有消息一起堵死。

## 3. Relay 的具体修改思路

### 第一步：不要忽略 `send()` 的返回结果

`KafkaTemplate.send()` 会返回一个代表“稍后才能知道的结果”的对象。Relay 要等待这个结果：

```java
for (OutboxEvent event : outboxRepository
        .findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()) {
    try {
        kafkaTemplate.send(
            event.getTopic(),
            event.getDedupKey(),
            event.getPayload()
        ).get(FUTURE_WAIT_SECONDS, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
    catch (Exception sendFailure) {
        FailureAction action = outboxStatusService
                .recordPublishFailure(event.getId(), sendFailure);

        if (action == FailureAction.QUARANTINED) {
            // 这条消息本身有问题。把它放到一边，继续处理下一条。
            continue;
        }

        // Kafka 暂时不可用。本轮结束，下一轮仍从这条开始。
        return;
    }

    try {
        // 只有 Kafka 明确确认成功后，才执行这一步。
        outboxStatusService.markPublished(event.getId());
    }
    catch (RuntimeException databaseFailure) {
        // Kafka 已经收到了，只是数据库暂时没标上。
        // 下轮可能再发一次，由 Consumer 的去重保护兜底。
        log.error("Kafka 已确认，但 Outbox 标记失败 id={}", event.getId(), databaseFailure);
        return;
    }
}
```

这里对 Future 最多等待 12 秒。`send()` 本身也可能同步等待 metadata 或可用 buffer，所以还要在 Kafka 配置中把 `max.block.ms` 设成 5 秒。这样不会继续使用默认的 60 秒阻塞上限。

等待秒数写成常量 `FUTURE_WAIT_SECONDS`，并从配置读取，这样第 9 节的超时测试才能把它调小，不必真的等 12 秒。

Kafka 暂时不可用时直接 `return`，本轮不再继续处理后面的 Outbox。这样不会让 50 条消息逐条等待，也更容易保持发送顺序。

如果等待超时，我们不知道 Kafka 最终有没有收到。此时宁可下一轮再发一次，也不能提前填写 `published_at`。

### 第二步：更新数据库时使用一个很短的事务

现在 `OutboxRelay.publish()` 整个方法带着 `@Transactional`。如果把等待 Kafka 的代码直接放进去，数据库事务也会跟着等待。

修改后：

- Relay 负责读消息、发 Kafka、等待结果。
- 一个单独的小 Service 负责填写 `published_at`，以及记录发送失败。
- 只有标记成功、记录失败这些很短的数据库操作使用事务。

最重要的是，发送 Kafka 和更新数据库必须分成两个 `try/catch`。如果 Kafka 已经成功、只是数据库更新失败，这不是“消息发送失败”，不能因此增加 `publish_attempts`，更不能把它隔离。下一轮重复发送一次是可以接受的，第 4、5 节会处理重复。

成功标记不再先 `findById()`，而是直接执行一条带条件的 UPDATE：

```java
@Modifying(clearAutomatically = true)
@Query("""
    update OutboxEvent o
       set o.publishedAt = :now,
           o.lastError = null
     where o.id = :id
       and o.publishedAt is null
       and o.failedAt is null
    """)
int markPublished(@Param("id") Long id, @Param("now") Instant now);
```

短事务 Service 只需要调用它：

```java
@Transactional
public void markPublished(Long id) {
    int updated = outboxRepository.markPublished(id, Instant.now());
    if (updated == 0) {
        log.info("Outbox 已经标记过、已隔离或已被删除 id={}", id);
    }
}
```

原来的 `findById(...).orElseThrow()` 有个问题：行万一不在了就抛异常，Relay 会误以为本轮处理失败。换成条件 UPDATE 后，更新 0 行只表示“现在已经没有需要更新的行”，不用重试，也不会堵住队列。

这样做主要是为了让数据库事务尽快开始、尽快结束，不要陪着 Kafka 一起等待。

### 第三步：一直发不出去的消息要能被放下

上面的“失败就 `return`”保证了发送顺序，但它带来一个新问题：**队头堵塞**。

Relay 每一轮都从最早那条没发出去的消息开始。如果第一条永远发不出去，它就会每一轮都失败、每一轮都 `return`，后面的消息一条也轮不到。这不是假设，至少有两种真实情况会触发：

```text
payload 超过 max.request.size  →  RecordTooLargeException，每轮都失败
topic 名称非法或没有权限        →  每轮都失败
```

第 6 节给 Consumer 配了“有限重试 + DLT”，Producer 这边也需要一个可以隔离坏消息的出口。

难点在于分辨两种失败：

```text
这条消息本身有问题   →  再试一万次也没用，应该隔离
Kafka 暂时不可用     →  过一会就好了，不能隔离
```

这里不要简单地写成“失败 5 次就隔离”。如果整个 Kafka 停了，所有正常消息都会连续失败；按次数直接隔离，会把本来没问题的消息也批量放到一边。

实现时分三种情况：

```text
明确是消息本身的问题   →  立刻写 failed_at，跳过
明确是 Kafka 暂时故障  →  保持待发送，本轮结束，下一轮重来
暂时分不清的错误       →  连续出现 5 次后写 failed_at，交给人检查
```

第一类只放明确属于这一行数据的问题，例如 `RecordTooLargeException`、`SerializationException`，或者 Outbox 行里保存了非法 topic。第二类主要是 Kafka 的 `RetriableException`、Relay 等待 Future 超时，以及认证、权限等整体配置问题；这些错误要停止本轮并告警，不能一路把后面的正常消息也隔离。第三类才使用 5 次上限；**明确的临时故障和整体配置故障都不使用次数上限**。

`recordPublishFailure()` 要把 Future 外层的 `ExecutionException` / `CompletionException` 拆开后再分类，并在自己的短事务里更新 `publish_attempts`、`last_error` 和必要时的 `failed_at`。它返回两个简单结果：

```java
enum FailureAction {
    RETRY_LATER,
    QUARANTINED
}
```

另外在 `OutboxWriter` 写入前做一次 payload 大小检查，例如把应用自己的上限设为 512 KiB。这样正常业务就不会先把明显超大的消息写进 Outbox；Relay 的隔离仍然保留，负责兜住漏网和配置变化的情况。

查询要跳过已隔离的消息，`pending()` 也不再把它们算进去，另外提供一个隔离计数供监控：

```java
List<OutboxEvent> findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc();

long countByPublishedAtIsNullAndFailedAtIsNull();

long countByFailedAtIsNotNull();
```

监控中再加两个数：`outbox_failed_count`（当前有多少条被隔离）和 `outbox_oldest_pending_age_seconds`（最老的待发送消息已经等了多久）。后者能发现 Kafka 长时间不可用，或 Relay 根本没有正常运行。

被隔离的消息和进了 DLT 的消息一样，都是“系统已经尽力，接下来交给人”。第一版不自动重发，也不删除原行。修好原因后，执行下面的操作即可让 Relay 重新捡起它：

```sql
UPDATE outbox
   SET failed_at = NULL,
       publish_attempts = 0,
       last_error = NULL
 WHERE id = :id;
```

## 4. 为什么仍然可能出现重复消息

即使等到了 Kafka 的成功回复，还是可能出现：

```text
Kafka 已经收到消息
    ↓
后端准备填写 published_at
    ↓
数据库连接失败，或者后端突然关闭
    ↓
published_at 仍然是 NULL
```

下次 Relay 会再次发送这条消息，所以 Kafka 里可能出现重复。

这是我们有意接受的结果：

> 宁可重复，也不要丢失。

Outbox 负责“不轻易漏发”，Consumer 负责“重复收到也不会重复处理”。

## 5. Consumer 怎么处理重复和用户互动

每条消息已经有一个 `dedupKey`，例如：

```text
BOOKING_CREATED:10
```

Consumer 会接触四张表，每张表只负责一件事：

```text
consumed_events    这条 Kafka 消息是否已经完整处理过
notifications      给用户看的通知
interactions       用户对活动做过什么
event_daily_metrics 当天有多少次预订和取消
```

### 5.1 用 `consumed_events` 防止重复处理

新增一张专门记录“哪些 Kafka 消息已经处理过”的表：

```sql
CREATE TABLE consumed_events (
    consumer_group VARCHAR(100) NOT NULL,
    dedup_key      VARCHAR(200) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, dedup_key)
);
```

之所以同时保存 `consumer_group` 和 `dedup_key`，是因为同一条 Kafka 消息以后可能由不同的 Consumer 处理。一个 Consumer 处理过，不代表其他 Consumer 也处理过。当前 `BookingConsumer` 继续使用 `eventpulse` 这个 group。

Consumer 收到消息后，先尝试写入：

```sql
INSERT INTO consumed_events (consumer_group, dedup_key)
VALUES ('eventpulse', :dedup_key)
ON CONFLICT DO NOTHING;
```

如果插入 0 行，说明以前已经完整处理过，直接结束。这样 Outbox 即使重复发送，也不会产生重复通知或重复的 `BOOK` / `CANCEL` 记录。

### 5.2 `interactions` 表具体记录什么

项目里已经有 `interactions` 表，不需要再创建一张新表。当前结构是：

```sql
CREATE TABLE interactions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

它保存的是行为历史，不是订单状态。例如：

```text
用户 3 在 10:01 查看活动 20       → VIEW
用户 3 在 10:03 收藏活动 20       → SAVE
用户 3 在 10:10 成功预订活动 20   → BOOK
用户 3 在 11:00 取消这笔预订       → CANCEL
```

完整类型包括 `VIEW`、`CLICK`、`SAVE`、`UNSAVE`、`BOOK`、`CANCEL`。其中前四种来自页面操作；`BOOK` 和 `CANCEL` 必须来自已经完成的后端预订事务，不能相信客户端自己上报，所以由 Kafka Consumer 写入。

这次只增加下面两个映射：

```text
BOOKING_CREATED      → 写一条 BOOK interaction
BOOKING_CANCELLED    → 写一条 CANCEL interaction
其他事件类型          → 仍可创建通知，但不写 BOOK/CANCEL interaction
```

尤其是 `EVENT_CANCELLED`，它表示主办方取消活动，不应伪装成“用户主动取消预订”，因此不会写 `CANCEL` interaction。

### 5.3 一条 Kafka 消息怎样同时写通知和互动

为了保持简单，这次不拆成两个 Consumer。仍由一个 `BookingConsumer` 在一个数据库事务里完成全部操作：

```text
收到 Kafka 消息
    ↓
尝试写 consumed_events
    ├── 没写进去：以前完整处理过，直接结束
    └── 写入成功
            ↓
        创建 notification
            ↓
        BOOKING_CREATED   → 创建 BOOK interaction
        BOOKING_CANCELLED → 创建 CANCEL interaction
        其他类型           → 不创建 interaction
            ↓
        整个事务提交
```

代码结构大概是：

```java
@KafkaListener(topics = "booking-events", groupId = "eventpulse")
@Transactional
public void onMessage(String json) {
    BookingEvent event = readEvent(json);

    boolean firstTime = consumedEvents.tryInsert(
        "eventpulse",
        event.dedupKey()
    );

    if (!firstTime) {
        return;
    }

    notifications.save(toNotification(event));

    switch (event.type()) {
        case "BOOKING_CREATED" ->
            interactionService.recordFromKafka(event, "BOOK");
        case "BOOKING_CANCELLED" ->
            interactionService.recordFromKafka(event, "CANCEL");
        default -> {
            // 其他事件只创建通知
        }
    }
}
```

这里的 `BookingEvent` 只是 Consumer 内部使用的小 DTO，至少包含 `type`、`dedupKey`、`userId`、`eventId`、`bookingId`、`title` 和 `message`，不需要做成新的数据库实体。

`recordFromKafka()` 负责写入 `interactions`，并沿用当前逻辑更新当天的 `event_daily_metrics.bookings` 或 `cancels`。它加入 Consumer 已经开启的事务，不另外开启一个独立事务。

同一个活动的多个订单可能几乎同时到达，所以每日统计不能使用“先查当前值、在 Java 里加 1、再保存”的方式。这里改为数据库直接执行 `bookings = bookings + 1` 或 `cancels = cancels + 1`；当天还没有统计行时就先创建。这样两个消息同时处理也不会互相覆盖。

对于 `BOOKING_CREATED` 和 `BOOKING_CANCELLED`，消息必须包含有效的 `dedupKey`、`userId`、`eventId` 和 `bookingId`。缺少这些字段时不能写一条内容不完整的 interaction，而是抛出异常，最终按第 6 节进入 DLT。

把这些操作放在同一个事务里非常重要。例如 interaction 保存失败时：

```text
consumed_events 已写入
notification 已创建
interaction 保存失败
    ↓
整个事务回滚
    ↓
四张表都回到处理前的状态
    ↓
Kafka 下次重试时可以从头完整处理
```

如果拆成多个事务，就可能留下“通知已经有了，但 BOOK 互动没有记录”的半成品。

当前 `notifications.dedup_key` 的唯一索引继续保留，作为第二层保护。`interactions` 不额外增加 `dedup_key`，因为 Kafka 的去重责任统一放在 `consumed_events`，并且这些数据在同一个事务里提交。

另外，当前 Consumer 会捕获并忽略所有异常。修改后，无论解析消息、保存通知、保存 interaction，还是更新每日统计失败，都要让异常继续抛出，交给下面的 Error Handler 重试；不能只写一条日志就结束。

## 6. Consumer 重试与 DLT

Consumer 在保存通知、互动或每日统计的任何一步失败后，先做有限次数的重试。仍然失败时，把原消息放进：

```text
booking-events.DLT
```

流程是：

```text
处理 booking-events
    ↓
失败后每隔 1 秒重试，共再试 4 次
    ↓
仍然失败
    ↓
可靠写入 booking-events.DLT
    ↓
原 Consumer 才继续处理后面的消息
```

新增一个明确的 Error Handler：

```java
@Bean
CommonErrorHandler kafkaErrorHandler(
        KafkaTemplate<Object, Object> kafkaTemplate) {

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(kafkaTemplate);

    // DLT 自己发送失败时，也不能把原消息当成处理成功
    recoverer.setFailIfSendResultIsError(true);

    return new DefaultErrorHandler(
        recoverer,
        new FixedBackOff(1_000L, 4L)
    );
}
```

这里选择“有限重试 + DLT”，而不是无限重试。这样一条始终有问题的消息不会永远卡住整个 partition，同时也不会只记日志后消失。

需要显式创建两个 Topic：

```text
booking-events
booking-events.DLT
```

DLT 不是终点。进入 DLT 的消息必须：

- 有日志和计数，方便发现。
- 保留原 topic、partition、offset、异常和 `dedupKey`。
- 修复原因后可以人工重新发送到 `booking-events`。
- 重放时仍然经过 `consumed_events`，避免重复通知和重复互动。

第一版不自动消费 DLT，避免坏消息在两个 Topic 之间循环。先提供清楚的人工查看和重放步骤即可。

## 7. Kafka 和调度配置也要说清楚

在 `application.yml` 中增加：

```yaml
spring:
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true
        max.block.ms: 5000
        request.timeout.ms: 5000
        delivery.timeout.ms: 10000
  task:
    scheduling:
      pool:
        size: 2
```

可以简单理解为：

- `acks: all`：要求 Kafka 更认真地确认消息已经收到。
- `enable.idempotence: true`：Producer 自己重试时，尽量避免重复写入。
- `max.block.ms: 5000`：`send()` 同步阻塞最多约 5 秒。
- `delivery.timeout.ms: 10000`：Producer 最多尝试投递约 10 秒。
- Relay 等 Future 12 秒：稍长于 Producer 的 10 秒投递窗口，避免 Relay 停止等待后 Producer 仍继续发送。
- 调度线程池设为 2：Relay 阻塞时，不会饿死活动生命周期定时任务。

不过这些配置不能代替 Consumer 的 `dedupKey` 去重，两边都要保留。

## 8. 需要修改的文件

### `OutboxRelay.java`

- 等待 `send()` 返回的最终结果，最多 12 秒；秒数写成常量并可配置。
- 只有成功后才标记 `published_at`。
- Kafka 发送失败与数据库成功标记失败分开处理，数据库失败不增加发送失败次数。
- Kafka 暂时故障时结束本轮，不继续等待后面的消息。
- 消息本身有问题时隔离这一条，本轮继续处理后面的消息。
- 只查 `published_at` 和 `failed_at` 都为空的消息。
- `pending()` 排除已隔离的消息，另加 `failed()` 返回隔离数量。
- 去掉包住整个发送过程的 `@Transactional`。

### 新增 `OutboxStatusService.java`

- 用一个很短的事务填写 `published_at`，改用带条件的 UPDATE。
- UPDATE 返回 0 行时不抛异常，也不让 Relay 重试这一行。
- 用一个很短的事务累加 `publish_attempts`、记录 `last_error`、必要时写 `failed_at`。
- 区分永久错误、临时错误和未知错误；临时错误不因为次数多就被隔离。

### `OutboxEvent.java` 和 `OutboxRepository.java`

- 实体新增 `publishAttempts`、`lastError`、`failedAt` 三个字段。
- Repository 新增条件 UPDATE、排除已隔离消息的查询和计数方法。

### `OutboxWriter.java`

- 写入前检查序列化后的 payload 大小，先挡住明显超过上限的消息。

### `BookingConsumer.java`

- 使用 `consumed_events` 和 `dedupKey` 判断消息是否处理过。
- 幂等记录、通知、`BOOK` / `CANCEL` interaction 和每日统计保存在同一个事务中。
- `BOOKING_CREATED` 映射为 `BOOK`，`BOOKING_CANCELLED` 映射为 `CANCEL`；其他消息不写这两种互动。
- 解析或数据库错误不再被静默忽略。

### 新增 `InteractionService.java`

- 从 `PlatformService` 中抽出现在的 interaction 保存和每日统计更新逻辑，避免 Consumer 再复制一份。
- 页面行为继续调用它记录 `VIEW` / `CLICK` / `SAVE` / `UNSAVE`。
- Kafka Consumer 调用它记录 `BOOK` / `CANCEL`。
- 方法加入调用方已有的事务，不使用新的独立事务。

### `EventDailyMetricRepository.java`

- 增加数据库原子加一的方法，供 `InteractionService` 更新 bookings 或 cancels。
- 避免多个 Kafka 消息同时处理时，读取同一个旧值并互相覆盖。

### 已有 `Interaction.java` 和 `interactions` 表

- 继续使用现有的 `user_id`、`event_id`、`type`、`created_at`，本次不用再建表。
- Kafka 去重交给 `consumed_events`，不在 `interactions` 里重复增加 `dedup_key`。

### 新增数据库迁移

- 新增 `V5__consumed_events.sql`：创建 `consumed_events` 表和联合主键。
- 新增 `V6__outbox_retry.sql`：

```sql
ALTER TABLE outbox ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN last_error       VARCHAR(1000);
ALTER TABLE outbox ADD COLUMN failed_at        TIMESTAMPTZ;

-- 已隔离的消息不该再出现在待发送索引里
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox (id)
    WHERE published_at IS NULL AND failed_at IS NULL;
```

### Outbox 监控

- 暴露待发送数、已隔离数，以及最老待发送消息的等待时间。
- 已隔离数大于 0，或最老消息等待过久时发出告警。
- 写清楚查看错误、修复原因和清空隔离字段后重发的人工步骤。

### 新增 Consumer 幂等 Repository

- 负责尝试写入 `consumer_group + dedup_key`。
- 返回是否首次处理，不负责创建通知或 interaction。

### 新增 Kafka Error Handler

- Consumer 失败后每隔 1 秒重试，最多再试 4 次。
- 最终失败时可靠写入 `booking-events.DLT`。
- DLT 发布失败时不能提交原消息的 offset。

### 新增 Kafka Topic 配置

- 显式创建 `booking-events` 和 `booking-events.DLT`。
- 两个 Topic 使用相同的 partition 数量。

### `application.yml`

- 增加 Kafka ACK、Producer 幂等、三个超时和调度线程池配置。

### 后端测试

- 更新现有 Outbox 测试，覆盖真正的异步成功和失败。

### `BookingProducer.java`

这是以前直接发送 Kafka 的旧代码。当前业务已经改用 `OutboxWriter`，应删除它的发送方法，避免以后有人不小心绕过 Outbox。

topic 名称可以移到一个简单的常量类中，供 Relay 和 Consumer 共用。

## 9. 需要验证哪些情况

### Kafka 成功

Kafka 返回成功后，`published_at` 被填写。

### Kafka 异步失败

`send()` 调用时没有报错，但返回结果后来失败。此时不能填写 `published_at`。

```java
when(kafkaTemplate.send(anyString(), anyString(), anyString()))
    .thenReturn(CompletableFuture.failedFuture(
        new RuntimeException("Kafka unavailable")
    ));

relay.publish();

verify(outboxStatusService, never())
    .markPublished(anyLong());
```

### Kafka 长时间没有回复

到达等待上限后结束本次处理，`published_at` 保持为空。测试时把等待时间设得很短，不真的等待 12 秒。

### 消息本身有问题时不堵住队列

准备两条待发送消息。第一条抛出不可重试的错误（例如 `RecordTooLargeException`），确认它立刻被写上 `failed_at`，并且**第二条在同一轮里仍然发了出去**。这是本次改动最关键的一个测试。

### Kafka 暂时不可用时不隔离

第一条抛出可重试的错误，确认 `publish_attempts` 加了 1、`failed_at` 仍为空，并且第二条这一轮不发送（保持顺序）。即使预置了很大的失败次数，也不能把明确的临时故障误判成坏消息。

### 未知错误的次数上限

让同一种无法分类的错误连续出现 5 次，确认消息被写上 `failed_at`；如果只出现 1～4 次，消息仍然保持待发送。

### 已隔离的消息不再被捡起

给一条消息写上 `failed_at`，确认查询不再返回它，`pending()` 不再统计它，`failed()` 能统计到它。人工清空 `failed_at`、`publish_attempts`、`last_error` 后，确认它重新进入待发送查询。

### 成功标记使用条件 UPDATE

确认 `markPublished()` 不会先查询实体。第一次调用更新 1 行；对已经标记、已经隔离或已经删除的 id 再调用时更新 0 行，而且不抛异常、不堵住 Relay。

### Kafka 已成功，但数据库标记失败

模拟 Kafka 已经返回成功、`markPublished()` 因数据库临时故障抛异常。确认 Relay 结束本轮，但不会调用 `recordPublishFailure()`，也不会增加 `publish_attempts` 或写入 `failed_at`。下一轮允许重新发送，由 Consumer 去重。

### payload 太大时尽早拒绝

让 `OutboxWriter` 收到超过应用上限的 payload，确认它在写入 Outbox 前就明确报错。再直接准备一条历史超大记录，确认 Relay 仍能把它隔离，避免后面的消息被堵住。

### Outbox 监控

确认待发送数不包含已隔离消息，已隔离数单独统计；最老待发送消息的等待时间也能正确计算。

### 重复消费

把相同 `BOOKING_CREATED` 消息交给 Consumer 两次，最终只能有一条 `consumed_events`、一条 notification 和一条 `BOOK` interaction，每日预订数也只能增加 1。

### Kafka 事件与 interaction 的映射

- `BOOKING_CREATED` 写入一条 `BOOK`，并增加当天的 bookings 统计。
- `BOOKING_CANCELLED` 写入一条 `CANCEL`，并增加当天的 cancels 统计。
- `EVENT_CANCELLED` 等其他消息仍然创建通知，但不写 `BOOK` 或 `CANCEL`。
- interaction 中的 `user_id`、`event_id` 必须来自 Kafka 消息，不能使用当前登录用户。
- 缺少 `dedupKey`、`userId`、`eventId` 或 `bookingId` 的预订消息不能写入 interaction，最终应进入 DLT。
- 并发处理同一活动的两条预订消息时，bookings 统计必须准确增加 2，不能丢掉一次更新。

### 下游保存失败时整体回滚

分别模拟通知保存失败、interaction 保存失败和每日统计更新失败。每一种情况下，整个事务都必须回滚，不能留下 `consumed_events` 或另一半业务数据；Error Handler 随后重新处理。

### 重试后进入 DLT

让 Consumer 持续失败，确认完成约定次数的重试后，原消息和错误信息进入 `booking-events.DLT`，正常 partition 可以继续向后处理。

### DLT 自己发送失败

模拟 `booking-events.DLT` 不可用，确认原消息不会被当成处理成功，也不会提交 offset。

### DLT 人工重放

把一条 DLT 消息重新发送到 `booking-events`，确认它能正常处理；如果之前已完成业务事务，则由 `consumed_events` 同时阻止重复通知和重复 interaction。

### 后端重启后补发

准备一条 `published_at = NULL` 的消息，再次运行 Relay，确认它会继续发送。

## 10. 建议的修改顺序

1. 新增 `V6__outbox_retry.sql` 和实体字段，让坏消息可以被隔离。
2. 修改 Repository：查询跳过隔离消息，`markPublished()` 使用条件 UPDATE。
3. 新增 `OutboxStatusService`，用短事务分别记录成功和发送失败。
4. 修改 `OutboxRelay`，等待 Kafka 的最终结果，并把 Kafka 发送失败与数据库标记失败分开处理。
5. 给 `OutboxWriter` 增加 payload 大小检查，并补上 Outbox 计数、最老等待时间和人工重发说明。
6. 增加 Kafka 超时配置和调度线程池。
7. 新增 `consumed_events` 表，抽出 `InteractionService`，再修改 Consumer 的去重、通知、`BOOK` / `CANCEL` 互动和异常处理。
8. 增加有限重试、DLT Topic、监控和人工重放步骤。
9. 补齐成功、失败、超时、失败隔离、条件 UPDATE、互动映射、事务回滚、重复消费、DLT 和重启补发测试。
10. 删除旧的直接发送 Kafka 代码。
11. 测试通过后，再更新总开发计划中的 Outbox 描述。

## 11. 怎么算修改完成

- Kafka 没有明确成功时，`published_at` 一定保持 `NULL`。
- Kafka 成功后才填写 `published_at`。
- Kafka 暂时不可用时，消息不会被提前标记，之后还能重试，也不会因为失败次数多而被误隔离。
- 一条永远发不出去的坏消息会被隔离，不会堵住排在它后面的消息。
- 被隔离的消息保留在 Outbox 中，可以查看原因并人工恢复；它不会被 `pending()` 混进待发送数。
- 能看到最老待发送消息已经等待多久，Relay 停摆或 Kafka 长时间故障不会悄悄发生。
- `markPublished()` 使用条件 UPDATE；行不存在或已经处理过时不会抛异常、不会堵住 Relay。
- Kafka 已经成功但数据库标记失败时，不会被误记成 Kafka 发送失败，也不会因此隔离消息。
- `BOOKING_CREATED` 会产生 `BOOK` interaction，`BOOKING_CANCELLED` 会产生 `CANCEL` interaction，其他事件不会冒充用户预订或取消。
- 同一消息发送两次时，`consumed_events`、消息中心、`interactions` 和每日统计都不会重复。
- 通知、interaction 或每日统计任意一步保存失败时，整个消费事务都会回滚，不会留下半成品，也不会阻止后续重试。
- Consumer 最终失败时，原消息能可靠进入 DLT，而不是只记日志后跳过。
- DLT 发送失败时，原消息不会被确认成功。
- 可以查看 DLT 数量，并按文档人工重放。
- 等待 Kafka 时不会一直占着数据库事务。
- Relay 阻塞时不会影响活动生命周期定时任务。
- 后端重启后，未确认的消息能够继续发送。
- 新增测试和原有后端测试全部通过。

## 12. 这次不做的事情

当前 EventPulse 是单节点教学项目。为了让修改保持简单，这次不增加复杂状态机、数据库抢锁、多节点任务分配、Saga 或分布式事务，也不做自动 DLT 修复。

这次也不把通知和 interaction 拆成两个 Consumer group。它们都属于当前后端对同一条预订事件建立的本地数据，并且需要一起成功或一起回滚，先放在一个 Consumer 事务里更容易讲清楚。以后如果通知变成独立服务，再拆分 group 和各自的 `consumed_events` 记录。

`outbox` 新增的 `publish_attempts` / `failed_at` 不算复杂状态机：它们只回答“发送失败过几次”和“是否已隔离”，没有额外的状态字段。被隔离的 Outbox 消息不自动重发，和 DLT 一样交给人工处理。

以后真的要同时运行多个后端实例时，再考虑这些设计。当前先把最关键的规则做对：

> Kafka 没有确认成功，就绝对不能填写 `published_at`。
