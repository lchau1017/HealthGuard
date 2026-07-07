export interface Env {
  OPENROUTER_API_KEY: string;
  MODEL_ID?: string;
}

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const DEFAULT_MODEL = "qwen/qwen2.5-vl-72b-instruct";

const EXTRACTION_PROMPT = `You are an OCR extraction engine for medication packaging.
Extract ONLY what is printed on the label. Do not infer, guess, or add medical advice.
For each field report a confidence 0-1. Use null when the label does not show the field.
frequency: {"timesPerDay":N} for "N times a day", {"everyHours":N} for "every N hours".`;

/** Wraps a value schema in the {value, confidence} envelope every field uses. */
function field(valueSchema: Record<string, unknown>) {
  return {
    type: "object",
    properties: {
      value: valueSchema,
      confidence: { type: "number", minimum: 0, maximum: 1 },
    },
    required: ["value", "confidence"],
  };
}

const SCHEMA = {
  type: "object",
  properties: {
    drugName: field({ type: ["string", "null"] }),
    activeIngredients: {
      type: "array",
      items: field({ type: ["string", "null"] }),
    },
    dosage: field({ type: ["string", "null"] }),
    form: field({
      enum: ["tablet", "capsule", "liquid", "spray", "cream", "other", null],
    }),
    frequency: field({
      type: ["object", "null"],
      properties: {
        timesPerDay: { type: "integer" },
        everyHours: { type: "integer" },
      },
    }),
    withFood: field({ type: ["boolean", "null"] }),
  },
  required: ["drugName", "activeIngredients", "dosage", "form", "frequency", "withFood"],
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/extract") {
      return json(404, { error: "not found" });
    }

    let image: unknown;
    try {
      const body = (await request.json()) as { imageJpegBase64?: unknown } | null;
      image = body?.imageJpegBase64;
    } catch {
      return json(400, { error: "imageJpegBase64 required" });
    }
    if (typeof image !== "string" || image.length === 0) {
      return json(400, { error: "imageJpegBase64 required" });
    }

    // The image is forwarded to the vision model and nowhere else: it is never
    // stored, logged, or echoed back in error responses.
    let upstream: Response;
    try {
      upstream = await fetch(OPENROUTER_URL, {
        method: "POST",
        headers: {
          authorization: `Bearer ${env.OPENROUTER_API_KEY}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          model: env.MODEL_ID ?? DEFAULT_MODEL,
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: EXTRACTION_PROMPT },
                {
                  type: "image_url",
                  image_url: { url: `data:image/jpeg;base64,${image}` },
                },
              ],
            },
          ],
          response_format: {
            type: "json_schema",
            json_schema: { name: "report_extraction", strict: true, schema: SCHEMA },
          },
        }),
      });
    } catch {
      return json(502, { error: "upstream" });
    }

    if (!upstream.ok) {
      return json(502, { error: "upstream" });
    }

    let completion: {
      choices?: { message?: { content?: unknown } }[];
    };
    try {
      completion = await upstream.json();
    } catch {
      return json(502, { error: "upstream" });
    }

    const content = completion?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || content.length === 0) {
      return json(502, { error: "no content" });
    }
    return new Response(content, { headers: { "content-type": "application/json" } });
  },
};
