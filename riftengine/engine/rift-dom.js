const SAFE_TAGS = new Set([
  'A','ARTICLE','ASIDE','B','BLOCKQUOTE','BR','BUTTON','CODE','DIV','EM','FOOTER','H1','H2','H3','H4','H5','H6',
  'HEADER','HR','I','IMG','LABEL','LI','MAIN','NAV','OL','P','PRE','SECTION','SMALL','SPAN','STRONG','UL'
]);

const SAFE_ATTRS = new Set(['alt','aria-label','class','href','id','role','src','title']);

function safeURL(value, attribute) {
  if (!['href','src'].includes(attribute)) return value;
  try {
    const url = new URL(value, location.href);
    if (['http:','https:','data:'].includes(url.protocol)) return url.href;
  } catch {}
  return '';
}

function sanitizeElement(element) {
  for (const child of [...element.children]) {
    if (!SAFE_TAGS.has(child.tagName)) {
      child.replaceWith(document.createTextNode(child.textContent || ''));
      continue;
    }
    for (const attribute of [...child.attributes]) {
      const name = attribute.name.toLowerCase();
      if (!SAFE_ATTRS.has(name) || name.startsWith('on')) {
        child.removeAttribute(attribute.name);
        continue;
      }
      if (name === 'href' || name === 'src') {
        const safe = safeURL(attribute.value, name);
        if (safe) child.setAttribute(name, safe);
        else child.removeAttribute(name);
      }
    }
    sanitizeElement(child);
  }
}

function renderNode(node) {
  if (!node || typeof node !== 'object') return document.createTextNode('');
  if (node.type === 'text') return document.createTextNode(String(node.text ?? ''));
  if (node.type === 'raw') {
    const template = document.createElement('template');
    template.innerHTML = String(node.html ?? '');
    sanitizeElement(template.content);
    return template.content;
  }

  const tag = String(node.tag || 'div').toUpperCase();
  const element = document.createElement(SAFE_TAGS.has(tag) ? tag.toLowerCase() : 'div');
  const attrs = node.attrs && typeof node.attrs === 'object' ? node.attrs : {};
  for (const [rawName, rawValue] of Object.entries(attrs)) {
    const name = String(rawName).toLowerCase();
    if (!SAFE_ATTRS.has(name) || name.startsWith('on')) continue;
    const value = safeURL(String(rawValue ?? ''), name);
    if (value || !['href','src'].includes(name)) element.setAttribute(name, value);
  }
  for (const child of Array.isArray(node.children) ? node.children : []) element.append(renderNode(child));
  return element;
}

export function renderRiftDOM(snapshot, target) {
  if (!target) return;
  target.replaceChildren();
  target.classList.add('rift-dom-surface');

  const body = snapshot?.body;
  if (!body) {
    target.textContent = 'RiftDOM returned an empty document.';
    return;
  }

  if (body.type === 'element' && String(body.tag).toLowerCase() === 'body') {
    for (const child of body.children || []) target.append(renderNode(child));
  } else {
    target.append(renderNode(body));
  }
}

export default renderRiftDOM;
