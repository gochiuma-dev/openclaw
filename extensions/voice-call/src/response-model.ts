// Voice Call plugin module implements response model behavior.
import type { OpenClawPluginApi } from "../api.js";
import type { VoiceCallConfig } from "./config.js";

// Resolves the model used for voice-call text response generation.

type ResolvedAgentModel = {
  provider: string;
  model: string;
};

/** Resolve provider/model fields from explicit voice config or the selected agent's defaults. */
export function resolveVoiceResponseModel(params: {
  voiceConfig: VoiceCallConfig;
  agentRuntime: OpenClawPluginApi["runtime"]["agent"];
  agentModel?: ResolvedAgentModel;
}): {
  modelRef: string;
  provider: string;
  model: string;
} {
  const defaultModel = params.agentModel ?? params.agentRuntime.defaults;
  const modelRef =
    params.voiceConfig.responseModel ?? `${defaultModel.provider}/${defaultModel.model}`;
  const slashIndex = modelRef.indexOf("/");

  return {
    modelRef,
    provider: slashIndex === -1 ? defaultModel.provider : modelRef.slice(0, slashIndex),
    model: slashIndex === -1 ? modelRef : modelRef.slice(slashIndex + 1),
  };
}
