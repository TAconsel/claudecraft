package com.consel.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Minimal MCP server (streamable HTTP transport, JSON responses only) on 127.0.0.1.
 * The claude CLI connects to it so Claude can call the game's tools during auto mode.
 * A random bearer token keeps other local programs from running commands as the player.
 */
public final class McpServer {
	public record Result(String text, boolean isError) {
		public static Result ok(final String text) {
			return new Result(text, false);
		}

		public static Result error(final String text) {
			return new Result(text, true);
		}
	}

	public record Tool(String name, String description, JsonObject inputSchema, Function<JsonObject, Result> handler) {
	}

	public static final String NAME = "minecraft";

	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final String token;
	private final HttpServer http;

	public McpServer(final List<Tool> toolList) throws IOException {
		for (final Tool t : toolList) {
			tools.put(t.name(), t);
		}
		final byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		token = HexFormat.of().formatHex(bytes);
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		http.createContext("/mcp", this::handle);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			final Thread t = new Thread(r, "ClaudeCraft MCP");
			t.setDaemon(true);
			return t;
		}));
		http.start();
	}

	/** Value for the CLI's --mcp-config. */
	public String mcpConfigJson() {
		final JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token);
		final JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp");
		server.add("headers", headers);
		final JsonObject servers = new JsonObject();
		servers.add(NAME, server);
		final JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root.toString();
	}

	/** Tool names as the CLI's --allowedTools expects them. */
	public List<String> allowedToolNames() {
		return tools.keySet().stream().map(n -> "mcp__" + NAME + "__" + n).toList();
	}

	private void handle(final HttpExchange ex) throws IOException {
		try (ex) {
			if (!("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
				send(ex, 401, null);
				return;
			}
			if (!"POST".equals(ex.getRequestMethod())) {
				// No server-initiated stream (GET) or session teardown (DELETE) support.
				send(ex, 405, null);
				return;
			}
			final JsonElement body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			if (body.isJsonArray()) {
				final JsonArray responses = new JsonArray();
				for (final JsonElement e : body.getAsJsonArray()) {
					final JsonObject r = dispatch(e.getAsJsonObject());
					if (r != null) {
						responses.add(r);
					}
				}
				send(ex, responses.isEmpty() ? 202 : 200, responses.isEmpty() ? null : responses.toString());
			} else {
				final JsonObject r = dispatch(body.getAsJsonObject());
				send(ex, r == null ? 202 : 200, r == null ? null : r.toString());
			}
		} catch (final RuntimeException e) {
			ClaudeCraftClient.LOGGER.warn("MCP request failed", e);
			send(ex, 400, null);
		}
	}

	private JsonObject dispatch(final JsonObject req) {
		if (!req.has("id") || req.get("id").isJsonNull()) {
			return null; // notification
		}
		final String method = req.has("method") ? req.get("method").getAsString() : "";
		final JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
		final JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", req.get("id"));
		switch (method) {
			case "initialize" -> {
				final JsonObject result = new JsonObject();
				result.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
				final JsonObject caps = new JsonObject();
				caps.add("tools", new JsonObject());
				result.add("capabilities", caps);
				final JsonObject info = new JsonObject();
				info.addProperty("name", "claudecraft");
				info.addProperty("version", "1.0.0");
				result.add("serverInfo", info);
				resp.add("result", result);
			}
			case "ping" -> resp.add("result", new JsonObject());
			case "tools/list" -> {
				final JsonArray list = new JsonArray();
				for (final Tool t : tools.values()) {
					final JsonObject o = new JsonObject();
					o.addProperty("name", t.name());
					o.addProperty("description", t.description());
					o.add("inputSchema", t.inputSchema());
					list.add(o);
				}
				final JsonObject result = new JsonObject();
				result.add("tools", list);
				resp.add("result", result);
			}
			case "tools/call" -> {
				final Tool tool = tools.get(params.has("name") ? params.get("name").getAsString() : "");
				Result r;
				if (tool == null) {
					r = Result.error("Unknown tool");
				} else {
					try {
						r = tool.handler().apply(params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject());
					} catch (final RuntimeException e) {
						ClaudeCraftClient.LOGGER.warn("Tool {} failed", tool.name(), e);
						r = Result.error("Tool failed: " + e);
					}
				}
				final JsonObject text = new JsonObject();
				text.addProperty("type", "text");
				text.addProperty("text", r.text());
				final JsonArray content = new JsonArray();
				content.add(text);
				final JsonObject result = new JsonObject();
				result.add("content", content);
				result.addProperty("isError", r.isError());
				resp.add("result", result);
			}
			default -> {
				final JsonObject err = new JsonObject();
				err.addProperty("code", -32601);
				err.addProperty("message", "Method not found: " + method);
				resp.add("error", err);
			}
		}
		return resp;
	}

	private static void send(final HttpExchange ex, final int status, final String json) throws IOException {
		if (json == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}
}
