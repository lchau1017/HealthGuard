import { afterEach, describe, expect, it, vi } from "vitest";
import worker, { type Env } from "../src/index";

const env: Env = { OPENROUTER_API_KEY: "test-key" };

function request(
  path: string,
  init?: RequestInit,
): Promise<Response> {
  return worker.fetch(
    new Request(`https://proxy.example${path}`, init) as never,
    env,
  ) as unknown as Promise<Response>;
}

function postExtract(body?: string): Promise<Response> {
  return request("/extract", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body,
  });
}

/** Stubs global fetch to return `response`; returns a getter for the captured upstream call. */
function stubUpstream(response: Response) {
  const spy = vi.fn(async () => response);
  vi.stubGlobal("fetch", spy);
  return spy;
}

function toolCallResponse(argumentsString: string): Response {
  return Response.json({
    choices: [
      {
        message: {
          tool_calls: [
            { function: { name: "report_extraction", arguments: argumentsString } },
          ],
        },
      },
    ],
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("routing", () => {
  it("returns 404 for GET /", async () => {
    const res = await request("/");
    expect(res.status).toBe(404);
  });

  it("returns 404 for GET /extract", async () => {
    const res = await request("/extract");
    expect(res.status).toBe(404);
  });

  it("returns 404 for POST to another path", async () => {
    const res = await request("/other", { method: "POST", body: "{}" });
    expect(res.status).toBe(404);
  });
});

describe("request validation", () => {
  it("returns 400 for POST /extract with no body", async () => {
    const res = await postExtract();
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "imageJpegBase64 required" });
  });

  it("returns 400 for POST /extract with {} body", async () => {
    const res = await postExtract("{}");
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "imageJpegBase64 required" });
  });

  it("returns 400 for POST /extract with invalid JSON", async () => {
    const res = await postExtract("not json {");
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "imageJpegBase64 required" });
  });
});

describe("successful extraction", () => {
  it("returns the tool call arguments verbatim as JSON", async () => {
    const args =
      '{"drugName":{"value":"Aspirin","confidence":0.97},"activeIngredients":[],"dosage":{"value":"500 mg","confidence":0.9},"form":{"value":"tablet","confidence":0.95},"frequency":{"value":{"timesPerDay":3},"confidence":0.8},"withFood":{"value":null,"confidence":0.2}}';
    stubUpstream(toolCallResponse(args));

    const res = await postExtract(JSON.stringify({ imageJpegBase64: "aGVsbG8=" }));

    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/json");
    expect(await res.text()).toBe(args);
  });
});

describe("upstream failures", () => {
  it("returns 502 upstream when OpenRouter responds 500", async () => {
    stubUpstream(new Response("boom", { status: 500 }));

    const res = await postExtract(JSON.stringify({ imageJpegBase64: "aGVsbG8=" }));

    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "upstream" });
  });

  it("returns 502 no tool call when the completion has no tool_calls", async () => {
    stubUpstream(
      Response.json({ choices: [{ message: { content: "I cannot do that" } }] }),
    );

    const res = await postExtract(JSON.stringify({ imageJpegBase64: "aGVsbG8=" }));

    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "no tool call" });
  });

  it("returns 502 upstream when fetch itself rejects", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new TypeError("network down");
    }));

    const res = await postExtract(JSON.stringify({ imageJpegBase64: "aGVsbG8=" }));

    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "upstream" });
  });
});

describe("upstream request shape", () => {
  it("sends the default model, forced tool choice and image data URL", async () => {
    const spy = stubUpstream(toolCallResponse("{}"));

    await postExtract(JSON.stringify({ imageJpegBase64: "aGVsbG8=" }));

    expect(spy).toHaveBeenCalledTimes(1);
    const [url, init] = spy.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://openrouter.ai/api/v1/chat/completions");
    expect((init.headers as Record<string, string>)["authorization"]).toBe(
      "Bearer test-key",
    );

    const body = JSON.parse(init.body as string);
    expect(body.model).toBe("qwen/qwen2.5-vl-72b-instruct");
    expect(body.tool_choice).toEqual({
      type: "function",
      function: { name: "report_extraction" },
    });
    expect(body.tools).toHaveLength(1);
    expect(body.tools[0].function.name).toBe("report_extraction");
    expect(body.tools[0].function.parameters.required).toEqual([
      "drugName",
      "activeIngredients",
      "dosage",
      "form",
      "frequency",
      "withFood",
    ]);

    const content = body.messages[0].content;
    expect(body.messages[0].role).toBe("user");
    expect(content[0].type).toBe("text");
    expect(content[0].text).toContain("OCR extraction engine");
    expect(content[1].type).toBe("image_url");
    expect(content[1].image_url.url).toBe("data:image/jpeg;base64,aGVsbG8=");
  });

  it("uses MODEL_ID from the environment when set", async () => {
    const spy = stubUpstream(toolCallResponse("{}"));

    await worker.fetch(
      new Request("https://proxy.example/extract", {
        method: "POST",
        body: JSON.stringify({ imageJpegBase64: "aGVsbG8=" }),
      }) as never,
      { ...env, MODEL_ID: "some/other-model" },
    );

    const body = JSON.parse(spy.mock.calls[0][1]!.body as string);
    expect(body.model).toBe("some/other-model");
  });
});
