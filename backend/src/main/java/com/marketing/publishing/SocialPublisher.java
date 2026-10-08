package com.marketing.publishing;

import java.util.*;

public interface SocialPublisher {
  record Result(
      String status,
      String remoteId,
      String url,
      String errorCode,
      long retrySeconds,
      String requestId) {}

  Map<String, Object> verify(String pageId, String token);

  Result publish(Map<String, Object> account, Map<String, Object> snapshot, UUID operation);

  Map<String, Object> fetchPost(Map<String, Object> account, String postId);

  Map<String, Number> metrics(Map<String, Object> account, String postId);
}
