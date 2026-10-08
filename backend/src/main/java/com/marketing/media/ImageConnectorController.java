package com.marketing.media;

import com.marketing.identity.Scope;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ImageConnectorController {
  private final Scope scope;
  private final ChatGptWebImages images;

  public ImageConnectorController(Scope scope, ChatGptWebImages images) {
    this.scope = scope;
    this.images = images;
  }

  @PostMapping("/v1/brands/{brand}/autopost/image-connector/pairing")
  Object pairing(@PathVariable UUID brand) {
    var member = scope.current();
    member.require();
    scope.brand(member, brand);
    return images.pairing();
  }
}
