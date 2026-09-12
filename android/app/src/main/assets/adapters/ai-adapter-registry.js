(() => {
  if (window.RiftAIAdapters) return;

  const common = Object.freeze({
    composer: ['textarea', '[contenteditable="true"]'],
    send: ['button[aria-label="Send" i]', 'button[aria-label*="Send message" i]', 'button[type="submit"]'],
    stop: ['button[aria-label^="Stop" i]', 'button[title^="Stop" i]'],
    assistant: ['[data-role="assistant"]', '[data-author="assistant"]', '[data-testid*="assistant-message"]'],
    user: ['[data-role="user"]', '[data-author="user"]', '[data-testid*="user-message"]']
  });

  const definitions = Object.freeze({
    chatgpt: Object.freeze({
      name: 'chatgpt', label: 'ChatGPT',
      composer: ['#prompt-textarea', '[data-testid*="composer"] [contenteditable="true"]', ...common.composer],
      send: ['[data-testid="send-button"]', ...common.send],
      stop: ['[data-testid="stop-button"]', ...common.stop],
      assistant: ['[data-message-author-role="assistant"]', ...common.assistant],
      user: ['[data-message-author-role="user"]', ...common.user]
    }),
    gemini: Object.freeze({
      name: 'gemini', label: 'Gemini',
      composer: ['rich-textarea [contenteditable="true"]', '.ql-editor[contenteditable="true"]', ...common.composer],
      send: ['button[aria-label*="Send message" i]', 'button[aria-label^="Send" i]', ...common.send],
      stop: ['button[aria-label*="Stop response" i]', ...common.stop],
      assistant: ['model-response', '[data-test-id="model-response"]', '.model-response', ...common.assistant],
      user: ['user-query', '[data-test-id="user-query"]', '.user-query-container', ...common.user]
    }),
    google: Object.freeze({
      name: 'google', label: 'Google AI',
      composer: ['rich-textarea [contenteditable="true"]', '.ql-editor[contenteditable="true"]', ...common.composer],
      send: ['button[aria-label*="Send message" i]', 'button[aria-label^="Send" i]', ...common.send],
      stop: ['button[aria-label*="Stop response" i]', ...common.stop],
      assistant: ['model-response', '[data-content-sender="assistant"]', ...common.assistant],
      user: ['user-query', '[data-content-sender="user"]', ...common.user]
    }),
    claude: Object.freeze({
      name: 'claude', label: 'Claude',
      composer: ['[contenteditable="true"].ProseMirror', 'fieldset [contenteditable="true"]', ...common.composer],
      send: ['button[aria-label*="Send message" i]', ...common.send],
      stop: ['button[aria-label*="Stop response" i]', ...common.stop],
      assistant: ['[data-testid="assistant-message"]', '.font-claude-response', ...common.assistant],
      user: ['[data-testid="user-message"]', '.font-user-message', ...common.user]
    }),
    copilot: Object.freeze({
      name: 'copilot', label: 'Copilot',
      composer: ['textarea[data-testid*="chat-input"]', '[contenteditable="true"][data-testid*="chat"]', ...common.composer],
      send: ['button[data-testid*="send"]', 'button[aria-label*="Send message" i]', ...common.send],
      stop: ['button[data-testid*="stop"]', ...common.stop],
      assistant: ['[data-content-sender="assistant"]', '.copilot-chat-assistant-message', ...common.assistant],
      user: ['[data-content-sender="user"]', '.copilot-chat-user-message', ...common.user]
    }),
    generic: Object.freeze({ name: 'generic', label: 'AI chat', ...common })
  });

  function resolve(hostname) {
    const host = String(hostname || location.hostname).toLowerCase();
    if (host.includes('chatgpt.com') || host.includes('openai.com')) return 'chatgpt';
    if (host.includes('github.com') || host.includes('copilot')) return 'copilot';
    if (host.includes('gemini.google.com')) return 'gemini';
    if (host === 'google.com' || host.endsWith('.google.com')) return 'google';
    if (host.includes('claude.ai')) return 'claude';
    return 'generic';
  }

  window.RiftAIAdapters = Object.freeze({
    version: 'rift-ai-adapters-v2',
    resolve,
    get(name) { return definitions[name] || definitions.generic; },
    current() { return definitions[resolve(location.hostname)] || definitions.generic; }
  });
})();
