# 订单是怎样完成的

这篇文档用日常语言说明：用户点击“确认预订”后，系统怎样扣余额、保存订单和电子票，以及把“预订成功”的消息交给后续功能。

这里说的“钱包”是站内余额。充值仍是演示功能，不会连接真实的银行卡或支付平台；但余额会真实参与站内预订和退款。

## 先看全貌

```text
用户确认预订
    ↓
确认活动可订、数量合理
    ↓
计算总价并扣除钱包余额
    ↓
锁定对应数量的余票
    ↓
保存订单和电子票
    ↓
把“预订成功”消息写入 Outbox
    ↓
所有步骤都成功，订单才算完成
    ↓
Outbox 在稍后把消息发送给通知、统计等功能
```

把它想成在商店结账：不能只把钱收走，却没有给顾客订单；也不能只留出座位，却忘记记账。订单流程会把这些关键动作当作同一件事来完成。

## 下单时发生的事

### 1. 先确认能不能订

系统会检查：

- 用户是否已经登录；
- 活动是否存在、已发布，而且还在可售时间内；
- 用户选择的数量是否至少为 1，且没有超过活动规定的单次上限；
- 活动是否还有足够的余票。

这一阶段不扣钱，也不占座位。任何一项不满足，用户会直接收到原因，例如“余票不足”或“单次最多预订 10 张”。

### 2. 计算本次应付金额

总价就是：

```text
活动单价 × 预订数量
```

例如单价 ¥120，订 2 张，总价就是 ¥240。系统把这个金额以“分”保存，避免小数计算带来的误差。

免费活动的总价是 0，依然会照常生成订单和电子票，只是不减少余额。

对应的实现只有一行，先把 `int` 转成更安全的 `long` 再相乘：

```java
long paidCents = Math.multiplyExact(
        (long) event.getPriceCents(),
        request.quantity());
```

### 3. 扣除余额，并避免“花超了”

系统不会先读出余额、再在程序里慢慢计算后保存，因为两次下单同时发生时，可能都以为余额够用。

它会直接提出一个简单条件：**只有余额不少于本次总价，才允许扣款。**

- 条件满足：扣款成功，流程继续；
- 条件不满足：返回“余额不足”，不会创建订单，也不会占用活动名额。

因此，即使用户很快地点了两次按钮，或同时在两个页面下单，也不会把钱包扣成负数。

下面这段就是“余额够才扣”的核心。它不是先把余额拿到程序里判断，而是直接让数据库完成判断和扣款：

```sql
UPDATE users
SET wallet_cents = wallet_cents - :amount
WHERE id = :userId
  AND wallet_cents >= :amount;
```

如果这条操作影响了 1 个用户，表示扣款成功；影响 0 个用户，就说明余额不够，流程会立刻停止：

```java
if (users.debitWalletIfEnough(userId, paidCents) == 0) {
    throw BusinessException.conflict("余额不足");
}
```

### 充值也遵循同一条规则

充值是另一笔独立操作，不属于某一次下单事务；但它也不能先读余额、在程序里相加后把旧余额写回。否则它可能覆盖刚刚完成的扣款或退款。

因此，充值同样通过一条“余额还没到上限才加钱”的原子操作完成：

```sql
UPDATE users
SET wallet_cents = wallet_cents + :amount
WHERE id = :userId
  AND wallet_cents <= :maxBalance - :amount;
```

这表示“在当前余额上加钱”，而不是“把余额改成我之前算好的某个数”。无论充值、下单扣款还是退款先后到达，它们都会以数据库中最新的余额继续计算，因此不会互相覆盖。

### 4. 占用活动名额

扣款通过后，系统才会把活动的已售数量加上本次购买的张数。

这一步同样会再次确认容量。原因很简单：可能有另一位用户在同一瞬间抢走了最后几张票。如果这时名额不足，整次下单都会作废，刚才的扣款也会自动还原。

库存更新也是“有条件才成功”：

```sql
UPDATE events
SET sold = sold + :qty
WHERE id = :id
  AND status = 'PUBLISHED'
  AND sold + :qty <= capacity;
```

### 5. 保存订单与电子票

名额成功占用后，系统会保存：

- 一张订单：谁订的、订了哪个活动、订了几张、订单状态；
- 本次实际支付的金额；
- 与订单数量一致的电子票。

“实际支付金额”是订单的价格快照。即使主办方日后修改活动价格，老订单的消费金额和退款金额都不会跟着改变。

订单保存时会把刚刚算出的金额一起写进去：

```java
Booking booking = new Booking();
booking.setUserId(userId);
booking.setEventId(event.getId());
booking.setQuantity(request.quantity());
booking.setPaidCents(paidCents); // 将本次实付金额固定在订单上
booking.setStatus("CONFIRMED");
bookings.save(booking);

ticketService.issue(booking.getId(), event.getId(), request.quantity());
```

### 6. 写入 Outbox：给后续功能留一张待办

订单完成后，系统还会在数据库里写入一条“预订已创建”的待办消息，这个待办就是 **Outbox**。

它并不是立刻弹给用户的通知，而是一张可靠的内部待办：之后由专门的发送程序读取它，再把消息送给通知、数据统计等功能。

这样做的好处是：即使消息服务当时临时不可用，订单也不会丢失；消息会留在 Outbox 中，等服务恢复后再发送。

写入待办时，系统会同时保存消息类型、唯一编号和内容。这里的唯一编号能帮助后续功能识别重复消息：

```java
outbox.write(
        KafkaTopics.BOOKING_EVENTS,
        "BOOKING_CREATED",
        "BOOKING_CREATED:" + booking.getId(),
        Map.of(
                "userId", userId,
                "eventId", event.getId(),
                "bookingId", booking.getId(),
                "quantity", request.quantity()));
```

## 为什么这些步骤要放在同一个事务里

可以把“事务”理解为一次下单的总开关：

- 所有步骤都成功，才按下“完成”；
- 中间任一步失败，就按下“撤销”，前面已经做过的修改一起恢复。

在本项目中，下列内容处在同一个总开关内：扣钱包余额、占用库存、保存订单、生成电子票、写入 Outbox。

代码里的 `@Transactional` 就是在声明这个“总开关”。它包住的是整段下单方法，而不是只包住扣款：

```java
@Transactional
public BookingVo create(CreateBookingRequest request) {
    // 扣余额 → 占名额 → 保存订单和电子票 → 写 Outbox
    // 任一步抛出错误，前面的修改都会一起撤销
}
```

### 这一次下单事务到底包含什么

可以把一次下单事务画成下面这个边界。方框中的内容要么一起成功，要么一起恢复；方框外的内容会在订单已经成功后再做。

```text
┌────────────── 下单事务（同进同退）──────────────┐
│  1. 读取活动并检查是否可订、数量是否合理         │
│  2. 按“余额足够才扣款”的规则扣除钱包余额         │
│  3. 按“剩余名额足够才增加已售数”的规则占用名额   │
│  4. 保存订单（包含本次实付金额）                 │
│  5. 生成对应数量的电子票                         │
│  6. 保存一条 Outbox 待办消息                     │
└─────────────────────────────────────────────────┘
                         ↓ 事务提交成功后
┌────────────── 后续处理（不属于下单事务）──────────┐
│  7. Outbox 发送程序把待办发送给消息系统            │
│  8. 通知用户、更新活动统计等                       │
└─────────────────────────────────────────────────┘
```

第 1 步主要是检查，本身不改变数据；但它和后续步骤放在同一段下单流程中，能确保检查通过后马上继续处理。真正会改变数据库的是第 2 到第 6 步。

| 如果在哪一步出错 | 事务最终会怎样 |
| --- | --- |
| 第 2 步余额不足 | 没有扣款，后面的步骤不会开始 |
| 第 3 步发现名额刚好被抢完 | 第 2 步已经扣掉的钱会恢复 |
| 第 4 步保存订单失败 | 钱和名额都会恢复 |
| 第 5 步生成电子票失败 | 钱、名额和订单都会恢复 |
| 第 6 步写 Outbox 失败 | 钱、名额、订单和电子票都会恢复 |

需要特别区分的是：**发送消息本身不在这个事务里。** 下单时只负责把待办安全地留在 Outbox；等订单已经确定成功后，后台才去发送消息。这样消息系统临时不可用时，不会影响用户下单，也不会让“订单成功了但通知任务消失”。

| 发生的问题 | 最终结果 |
| --- | --- |
| 余额不足 | 不扣钱、不占名额、不创建订单 |
| 最后一刻发现余票不足 | 已尝试的扣款会还原，不创建订单 |
| 保存电子票失败 | 扣款和名额占用都会还原 |
| 写入 Outbox 失败 | 订单本身也不会完成，避免“有订单却没有后续消息” |

这保证用户不会遇到“钱扣了却没有订单”，主办方也不会遇到“名额被占了却找不到订单”的情况。

## 订单取消与退款

用户取消一个已确认订单时，系统会：

1. 先确认这张订单还没有取消过；
2. 将订单标为已取消；
3. 按订单保存的实际支付金额退回钱包；
4. 归还活动名额，并让相关电子票失效；
5. 写入一条“订单已取消”的 Outbox 待办。

主办方取消整个活动时，对每一张仍有效的订单做同样的退款处理。系统会确保同一张订单只能退款一次，因此重复点击取消不会得到两次退款。

这里先用“只有仍在已确认状态才能改为已取消”的方式领取退款资格；领取失败就表示这张订单已经被别人取消过：

```sql
UPDATE bookings
SET status = 'CANCELLED', cancelled_at = now()
WHERE id = :id AND status = 'CONFIRMED';
```

领取成功后，才会退回订单原本记录的实付金额：

```java
users.creditWallet(booking.getUserId(), booking.getPaidCents());
```

## Outbox 消息后来去了哪里

Outbox 中的待办会由后台发送程序逐条处理：

```text
Outbox 待办
    ↓
发送成功后标记为已完成
    ↓
通知用户、更新活动统计等后续工作
```

如果发送失败，待办不会被当成已完成，会在之后继续尝试。即使因为网络原因产生重复投递，接收方也会根据消息的唯一标识避免重复创建同一条通知或重复统计。

## 对照代码阅读（可选）

如果你想把这篇说明和代码对应起来，可以从下面几个位置开始：

- 下单与用户取消订单：[BookingService.java](../backend/src/main/java/dev/kaiwen/eventpulse/service/BookingService.java)
- 安全扣款与退款的余额操作：[UserRepository.java](../backend/src/main/java/dev/kaiwen/eventpulse/repository/UserRepository.java)
- 防止重复退款的订单取消操作：[BookingRepository.java](../backend/src/main/java/dev/kaiwen/eventpulse/repository/BookingRepository.java)
- 写入待办消息：[OutboxWriter.java](../backend/src/main/java/dev/kaiwen/eventpulse/outbox/OutboxWriter.java)
- 订单实付金额字段：[V7__booking_wallet_payments.sql](../backend/src/main/resources/db/migration/V7__booking_wallet_payments.sql)
