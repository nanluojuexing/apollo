package com.ctrip.framework.apollo.configservice.controller;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.message.ReleaseMessageListener;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.utils.EntityManagerUtil;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.configservice.service.ReleaseMessageServiceWithCache;
import com.ctrip.framework.apollo.configservice.util.NamespaceUtil;
import com.ctrip.framework.apollo.configservice.util.WatchKeysUtil;
import com.ctrip.framework.apollo.configservice.wrapper.DeferredResultWrapper;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 *
 * 实现 ReleaseMessageListener 接口，通知 Controller ，仅提供 notifications/v2 接口
 *
 * 客户端的轮训就是通过该类实现
 *
 * @author Jason Song(song_s@ctrip.com)
 */
@RestController
@RequestMapping("/notifications/v2")
public class NotificationControllerV2 implements ReleaseMessageListener {
  private static final Logger logger = LoggerFactory.getLogger(NotificationControllerV2.class);
  /**
   * Watch Key 与 DeferredResultWrapper 的 Multimap
   *
   * Key：Watch Key
   * Value：DeferredResultWrapper 数组
   */
  private final Multimap<String, DeferredResultWrapper> deferredResults =
      Multimaps.synchronizedSetMultimap(TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, Ordering.natural()));
  private static final Splitter STRING_SPLITTER =
      Splitter.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR).omitEmptyStrings();
  private static final Type notificationsTypeReference =
      new TypeToken<List<ApolloConfigNotification>>() {
      }.getType();

  private final ExecutorService largeNotificationBatchExecutorService;

  private final WatchKeysUtil watchKeysUtil;
  private final ReleaseMessageServiceWithCache releaseMessageService;
  private final EntityManagerUtil entityManagerUtil;
  private final NamespaceUtil namespaceUtil;
  private final Gson gson;
  private final BizConfig bizConfig;

  @Autowired
  public NotificationControllerV2(
      final WatchKeysUtil watchKeysUtil,
      final ReleaseMessageServiceWithCache releaseMessageService,
      final EntityManagerUtil entityManagerUtil,
      final NamespaceUtil namespaceUtil,
      final Gson gson,
      final BizConfig bizConfig) {
    largeNotificationBatchExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create
        ("NotificationControllerV2", true));
    this.watchKeysUtil = watchKeysUtil;
    this.releaseMessageService = releaseMessageService;
    this.entityManagerUtil = entityManagerUtil;
    this.namespaceUtil = namespaceUtil;
    this.gson = gson;
    this.bizConfig = bizConfig;
  }

  /**
   * 检测配置变更
   * @param appId 应用id
   * @param cluster 集群名称
   * @param notificationsAsString 通知对象的 json 字符串  客户端会将自己所有的 namespace 传递到服务端进行查询
   * @param dataCenter idc 属性
   * @param clientIp
   * @return
   */
  @GetMapping
  public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> pollNotification(
      @RequestParam(value = "appId") String appId,
      @RequestParam(value = "cluster") String cluster,
      @RequestParam(value = "notifications") String notificationsAsString,
      @RequestParam(value = "dataCenter", required = false) String dataCenter,
      @RequestParam(value = "ip", required = false) String clientIp) {
    List<ApolloConfigNotification> notifications = null;

    try {
      // 转换对象
      notifications = gson.fromJson(notificationsAsString, notificationsTypeReference);
    } catch (Throwable ex) {
      Tracer.logError(ex);
    }

    if (CollectionUtils.isEmpty(notifications)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }

    // Spring的异步对象 timeout为 60
    DeferredResultWrapper deferredResultWrapper = new DeferredResultWrapper(bizConfig.longPollingTimeoutInMilli());
    Set<String> namespaces = Sets.newHashSet();
    // 客户端的通知 Map 。key 为 Namespace 名，value 为通知编号。
    Map<String, Long> clientSideNotifications = Maps.newHashMap();
    Map<String, ApolloConfigNotification> filteredNotifications = filterNotifications(appId, notifications);


    for (Map.Entry<String, ApolloConfigNotification> notificationEntry : filteredNotifications.entrySet()) {
      String normalizedNamespace = notificationEntry.getKey();
      ApolloConfigNotification notification = notificationEntry.getValue();
      namespaces.add(normalizedNamespace);
      // 添加到 client 端的通知, key 是 namespace, values 是 messageId
      clientSideNotifications.put(normalizedNamespace, notification.getNotificationId());
      // 如果不相等, 记录客户端名字
      if (!Objects.equals(notification.getNamespaceName(), normalizedNamespace)) {
        // 记录 key = 标准名字, value = 客户端名字
        deferredResultWrapper.recordNamespaceNameNormalizedResult(notification.getNamespaceName(), normalizedNamespace);
      }
    }

    if (CollectionUtils.isEmpty(namespaces)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }
    // 组装得到需要观察的 key,包括公共的.
    Multimap<String, String> watchedKeysMap =
        watchKeysUtil.assembleAllWatchKeys(appId, cluster, namespaces, dataCenter);

    // 得到 value; 这个 value 也就是 appId + cluster + namespace
    Set<String> watchedKeys = Sets.newHashSet(watchedKeysMap.values());

    /**
     * 1. 设置 deferredResult 参数
     * 1、set deferredResult before the check, for avoid more waiting
     * If the check before setting deferredResult,it may receive a notification the next time
     * when method handleMessage is executed between check and set deferredResult.
     */
    // 设置 timeout 回调:打印日志
    deferredResultWrapper
          .onTimeout(() -> logWatchedKeys(watchedKeys, "Apollo.LongPoll.TimeOutKeys"));

    // 设置完成回调:删除 key
    deferredResultWrapper.onCompletion(() -> {
      //unregister all keys
      //取消注册
      for (String key : watchedKeys) {
        deferredResults.remove(key, deferredResultWrapper);
      }
      //register all keys 注册
      logWatchedKeys(watchedKeys, "Apollo.LongPoll.CompletedKeys");
    });

    //register all keys
    for (String key : watchedKeys) {
      this.deferredResults.put(key, deferredResultWrapper);
    }

    logWatchedKeys(watchedKeys, "Apollo.LongPoll.RegisteredKeys");
    logger.debug("Listening {} from appId: {}, cluster: {}, namespace: {}, datacenter: {}",
        watchedKeys, appId, cluster, namespaces, dataCenter);

    /**
     * 2、check new release 检查是否有最新的消息 从缓存中获取
     */
    List<ReleaseMessage> latestReleaseMessages =
        releaseMessageService.findLatestReleaseMessagesGroupByMessages(watchedKeys);

    /**
     * Manually close the entity manager.
     * Since for async request, Spring won't do so until the request is finished,
     * which is unacceptable since we are doing long polling - means the db connection would be hold
     * for a very long time
     */
    // 贯关闭数据库，释放资源
    entityManagerUtil.closeEntityManager();

    // 计算新的通知
    List<ApolloConfigNotification> newNotifications =
        getApolloConfigNotifications(namespaces, clientSideNotifications, watchedKeysMap,
            latestReleaseMessages);
    // 不是空, 立即返回结果, 不等待
    if (!CollectionUtils.isEmpty(newNotifications)) {
      deferredResultWrapper.setResult(newNotifications);
    }

    return deferredResultWrapper.getResult();
  }

  /**
   * 过滤并创建 ApolloConfigNotification Map 。其中，KEY 为 Namespace 的名字
   * @param appId
   * @param notifications
   * @return
   */
  private Map<String, ApolloConfigNotification> filterNotifications(String appId,
                                                                    List<ApolloConfigNotification> notifications) {
    Map<String, ApolloConfigNotification> filteredNotifications = Maps.newHashMap();
    for (ApolloConfigNotification notification : notifications) {
      if (Strings.isNullOrEmpty(notification.getNamespaceName())) {
        continue;
      }
      //strip out .properties suffix
      // 若 Namespace 名以 .properties 结尾，移除该结尾，并设置到 ApolloConfigNotification 中。例如 application.properties => application
      String originalNamespace = namespaceUtil.filterNamespaceName(notification.getNamespaceName());
      notification.setNamespaceName(originalNamespace);
      //fix the character case issue, such as FX.apollo <-> fx.apollo
      // 获得归一化的 Namespace 名字。因为，客户端 Namespace 会填写错大小写。
      // 例如，数据库中 Namespace 名为 Fx.Apollo ，而客户端 Namespace 名为 fx.Apollo
      // 通过归一化后，统一为 Fx.Apollo
      String normalizedNamespace = namespaceUtil.normalizeNamespace(appId, originalNamespace);

      // in case client side namespace name has character case issue and has difference notification ids
      // such as FX.apollo = 1 but fx.apollo = 2, we should let FX.apollo have the chance to update its notification id
      // which means we should record FX.apollo = 1 here and ignore fx.apollo = 2
      if (filteredNotifications.containsKey(normalizedNamespace) &&
          filteredNotifications.get(normalizedNamespace).getNotificationId() < notification.getNotificationId()) {
        continue;
      }

      filteredNotifications.put(normalizedNamespace, notification);
    }
    return filteredNotifications;
  }

  /**
   * 新的 ApolloConfigNotification 通知数组
   * @param namespaces
   * @param clientSideNotifications
   * @param watchedKeysMap
   * @param latestReleaseMessages
   * @return
   */
  private List<ApolloConfigNotification> getApolloConfigNotifications(Set<String> namespaces,
                                                                      Map<String, Long> clientSideNotifications,
                                                                      Multimap<String, String> watchedKeysMap,
                                                                      List<ReleaseMessage> latestReleaseMessages) {
    List<ApolloConfigNotification> newNotifications = Lists.newArrayList();
    if (!CollectionUtils.isEmpty(latestReleaseMessages)) {
      Map<String, Long> latestNotifications = Maps.newHashMap();
      for (ReleaseMessage releaseMessage : latestReleaseMessages) {
        latestNotifications.put(releaseMessage.getMessage(), releaseMessage.getId());
      }
      // 循环 Namespace 的名字的集合，判断是否有配置更新
      for (String namespace : namespaces) {
        long clientSideId = clientSideNotifications.get(namespace);
        long latestId = ConfigConsts.NOTIFICATION_ID_PLACEHOLDER;
        Collection<String> namespaceWatchedKeys = watchedKeysMap.get(namespace);
        // 获得最大的通知编号
        for (String namespaceWatchedKey : namespaceWatchedKeys) {
          long namespaceNotificationId =
              latestNotifications.getOrDefault(namespaceWatchedKey, ConfigConsts.NOTIFICATION_ID_PLACEHOLDER);
          if (namespaceNotificationId > latestId) {
            latestId = namespaceNotificationId;
          }
        }
        // 若服务器的通知编号大于客户端的通知编号，意味着有配置更新
        if (latestId > clientSideId) {
          ApolloConfigNotification notification = new ApolloConfigNotification(namespace, latestId);
          namespaceWatchedKeys.stream().filter(latestNotifications::containsKey).forEach(namespaceWatchedKey ->
              notification.addMessage(namespaceWatchedKey, latestNotifications.get(namespaceWatchedKey)));
          newNotifications.add(notification);
        }
      }
    }
    return newNotifications;
  }

  /**
   * 当消息扫描器扫描到新的消息时，会通知所有的监听器，也就是执行 handlerMessage 方法
   * @param message
   * @param channel
   */
  @Override
  public void handleMessage(ReleaseMessage message, String channel) {
    logger.info("message received - channel: {}, message: {}", channel, message);

    // 消息类型必须是 “apollo-release”。然后拿到消息里的 namespace 内容
    String content = message.getMessage();
    Tracer.logEvent("Apollo.LongPoll.Messages", content);
    if (!Topics.APOLLO_RELEASE_TOPIC.equals(channel) || Strings.isNullOrEmpty(content)) {
      return;
    }

    // 获得变更的namespace
    String changedNamespace = retrieveNamespaceFromReleaseMessage.apply(content);

    if (Strings.isNullOrEmpty(changedNamespace)) {
      logger.error("message format invalid - {}", content);
      return;
    }

    if (!deferredResults.containsKey(content)) {
      return;
    }

    //create a new list to avoid ConcurrentModificationException  构造一个新 list ,防止并发问题
    List<DeferredResultWrapper> results = Lists.newArrayList(deferredResults.get(content));

    // 构建通知的对象
    ApolloConfigNotification configNotification = new ApolloConfigNotification(changedNamespace, message.getId());
    configNotification.addMessage(content, message.getId());

    // 如果有超过 100 个客户端在等待，那么就使用线程池批量执行通知。否则就同步慢慢执行
    //do async notification if too many clients 如果有大量的客户端(100)在等待,使用线程池异步处理
    if (results.size() > bizConfig.releaseMessageNotificationBatch()) {
      largeNotificationBatchExecutorService.submit(() -> {
        logger.debug("Async notify {} clients for key {} with batch {}", results.size(), content,
            bizConfig.releaseMessageNotificationBatch());
        for (int i = 0; i < results.size(); i++) {
          /*
           * 假设一个公共 Namespace 有10W 台机器使用，如果该公共 Namespace 发布时直接下发配置更新消息的话，
           * 就会导致这 10W 台机器一下子都来请求配置，这动静就有点大了，而且对 Config Service 的压力也会比较大。
           * 即"惊群效应"
           */
          if (i > 0 && i % bizConfig.releaseMessageNotificationBatch() == 0) {
            try {
              TimeUnit.MILLISECONDS.sleep(bizConfig.releaseMessageNotificationBatchIntervalInMilli());
            } catch (InterruptedException e) {
              //ignore
            }
          }
          logger.debug("Async notify {}", results.get(i));
          // 通知每个等待的 HTTP 请求
          results.get(i).setResult(configNotification);
        }
      });
      return;
    }

    logger.debug("Notify {} clients for key {}", results.size(), content);

    for (DeferredResultWrapper result : results) {
      result.setResult(configNotification);
    }
    logger.debug("Notification completed");
  }

  /**
   * 通过 ReleaseMessage 的消息内容，获得对应 Namespace 的名字
   */
  private static final Function<String, String> retrieveNamespaceFromReleaseMessage =
      releaseMessage -> {
        if (Strings.isNullOrEmpty(releaseMessage)) {
          return null;
        }
        List<String> keys = STRING_SPLITTER.splitToList(releaseMessage);
        //message should be appId+cluster+namespace
        if (keys.size() != 3) {
          logger.error("message format invalid - {}", releaseMessage);
          return null;
        }
        return keys.get(2);
      };

  private void logWatchedKeys(Set<String> watchedKeys, String eventName) {
    for (String watchedKey : watchedKeys) {
      Tracer.logEvent(eventName, watchedKey);
    }
  }
}
