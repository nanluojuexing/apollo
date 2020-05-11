package com.ctrip.framework.apollo.biz.message;

import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;

/**
 * 监听器，检测消息
 * @author Jason Song(song_s@ctrip.com)
 */
public interface ReleaseMessageListener {
  void handleMessage(ReleaseMessage message, String channel);
}
