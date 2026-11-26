// The round agent button face: monogram, accent ring and state badge. No provider logos.
import { icon } from './icons.js';
import { h, setStyle } from './dom.js';

/**
 * @param {import('../../types/contract').AgentSummary} agent
 * @param {{ size?: 24 | 32 | 40 | 72 }} [opts]
 * @returns {HTMLElement}
 */
export function agentCircle(agent, opts = {}) {
  const el = h('span', { class: 'agent-circle', 'aria-hidden': 'true' });
  updateAgentCircle(el, agent, opts);
  return el;
}

/**
 * @param {HTMLElement} el
 * @param {import('../../types/contract').AgentSummary} agent
 * @param {{ size?: 24 | 32 | 40 | 72 }} [opts]
 */
export function updateAgentCircle(el, agent, opts = {}) {
  const size = opts.size || 32;
  const key = `${agent.monogram}|${agent.state}|${agent.busy}|${size}|${agent.accent}`;
  if (el.dataset.render === key) return;
  el.dataset.render = key;
  setStyle(el, '--size', `${size}px`);
  setStyle(el, '--agent-accent', agent.accent);
  const len = [...agent.monogram].length;
  setStyle(el, '--mono-scale', len >= 3 ? '0.3' : len === 2 ? '0.36' : '0.44');
  el.dataset.state = agent.state;
  if (agent.busy) el.dataset.busy = '';
  else delete el.dataset.busy;
  el.replaceChildren(h('span', { class: 'agent-circle__mono', text: agent.monogram }));
  if (size >= 24) {
    if (agent.state === 'signedOut') el.append(h('span', { class: 'agent-circle__badge' }, icon('lock', 12)));
    else if (agent.state === 'signingIn') el.append(h('span', { class: 'agent-circle__badge agent-circle__badge--spin' }));
    else el.append(h('span', { class: 'agent-circle__badge agent-circle__badge--dot' }));
  }
}
