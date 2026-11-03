package com.fluxagent.agent;

import com.fluxagent.llm.LlmGateway;
import com.fluxagent.skill.SkillManager;

public class GeneralAgent extends BaseAgent {

    public GeneralAgent(LlmGateway llmGateway, SkillManager skillManager) {
        super(llmGateway, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.GENERAL;
    }

    @Override
    protected String systemPrompt() {
        return "你是 FluxAgent 智能客服。友好、简洁地回答用户问题。如果问题超出能力范围，说明原因并建议转接专业客服。";
    }
}
