package com.ctrip.framework.apollo.core.dto;

/**
 * Apollo 配置通知 DTO
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class ApolloConfigNotification {
  private String namespaceName;
  /**
   * 最新通知编号
   *
   * 目前使用 `ReleaseMessage.id` 。
   */
  private long notificationId;
  private volatile ApolloNotificationMessages messages;

  //for json converter
  public ApolloConfigNotification() {
  }

  public ApolloConfigNotification(String namespaceName, long notificationId) {
    this.namespaceName = namespaceName;
    this.notificationId = notificationId;
  }

  public String getNamespaceName() {
    return namespaceName;
  }

  public long getNotificationId() {
    return notificationId;
  }

  public void setNamespaceName(String namespaceName) {
    this.namespaceName = namespaceName;
  }

  public ApolloNotificationMessages getMessages() {
    return messages;
  }

  public void setMessages(ApolloNotificationMessages messages) {
    this.messages = messages;
  }

  public void addMessage(String key, long notificationId) {
    if (this.messages == null) {
      synchronized (this) {
        if (this.messages == null) {
          this.messages = new ApolloNotificationMessages();
        }
      }
    }
    this.messages.put(key, notificationId);
  }

  @Override
  public String toString() {
    return "ApolloConfigNotification{" +
        "namespaceName='" + namespaceName + '\'' +
        ", notificationId=" + notificationId +
        '}';
  }
}
