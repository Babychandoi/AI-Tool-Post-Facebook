package com.marketing.ai;

import com.marketing.identity.Scope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/ai/chatgpt")
public class ChatGptController {
  private final Scope scope;
  private final ChatGptConnections connections;

  public ChatGptController(Scope scope, ChatGptConnections connections) {
    this.scope = scope;
    this.connections = connections;
  }

  @GetMapping
  Object status() {
    return connections.status(scope.current());
  }

  public record Start(UUID connection_id, boolean consent) {}

  @PostMapping("/connect")
  Object start(@RequestBody Start start, HttpServletRequest request) {
    return Map.of(
        "authorization_url",
        connections.start(
            scope.current(), request.getSession(), start.connection_id(), start.consent()));
  }

  @GetMapping("/callback")
  ResponseEntity<?> callback(
      HttpServletRequest request,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String client_id,
      @RequestParam(required = false) String error) {
    String result;
    try {
      result =
          connections.callback(
              scope.current(), request.getSession(), state, code, client_id, error);
    } catch (Exception e) {
      result = "error";
    }
    return ResponseEntity.status(303)
        .location(URI.create("/?chatgpt=" + result + "#ai"))
        .header("Cache-Control", "no-store")
        .build();
  }

  @PostMapping("/{id}/activate")
  Object activate(@PathVariable UUID id) {
    connections.activate(scope.current(), id);
    return Map.of("ok", true);
  }

  @GetMapping("/{id}/models")
  Object models(@PathVariable UUID id) {
    return connections.models(scope.current(), id);
  }

  public record Model(@NotBlank @Size(max = 200) String model) {}

  @PutMapping("/{id}/model")
  Object model(@PathVariable UUID id, @Valid @RequestBody Model model) {
    connections.selectModel(scope.current(), id, model.model());
    return Map.of("ok", true);
  }

  @DeleteMapping("/{id}")
  Object disconnect(@PathVariable UUID id) {
    return Map.of(
        "ok", true, "remote_revocation_confirmed", connections.disconnect(scope.current(), id));
  }
}
