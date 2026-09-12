(() => {
  if (window.RiftAIAdapters) return;
  window.RiftAIAdapters = Object.freeze({
    version: 'rift-ai-adapters-v1',
    resolve(hostname) {
      const host = String(hostname || location.hostname).toLowerCase();
      if (host.includes('chatgpt.com') || host.includes('openai.com')) return 'chatgpt';
      if (host.includes('github.com') || host.includes('copilot')) return 'copilot';
      if (host.includes('gemini.google.com') || host.includes('google.com')) return 'gemini';
      if (host.includes('claude.ai')) return 'claude';
      return 'generic';
    }
  });
})();
