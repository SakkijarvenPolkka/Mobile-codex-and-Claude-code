// The floating dock: one round button per agent.
import { h, q, syncList } from '../common/dom.js';
import { icon } from '../common/icons.js';
import { applyBoot, t } from '../common/boot.js';
import { agentCircle, updateAgentCircle } from '../common/agent-circle.js';

const api = /** @type {import('../../types/contract').DockApi} */ (window.trio.dock);
const dock = q('#dock');
const agents = q('#agents');
const grip = q('#grip');
const menu = /** @type {HTMLButtonElement} */ (q('#dock-menu'));

/** @param {import('../../types/contract').DockState} s */
function render(s) {
  syncList(
    agents,
    s.agents.filter((a) => a.enabled),
    (a) => a.id,
    (a) => {
      const b = h('button', { class: 'dock-agent', 'data-testid': 'dock-agent', dataset: { agentId: a.id } }, agentCircle(a, { size: 40 }));
      b.addEventListener('click', () => api.toggleAgent(a.id));
      return b;
    },
    (b, a) => {
      updateAgentCircle(/** @type {HTMLElement} */ (b.firstElementChild), a, { size: 40 });
      b.setAttribute('aria-pressed', String(a.open));
      const label = a.state === 'signedIn' ? t('chrome.agentOpen', { name: a.name }) : t('chrome.agentLocked', { name: a.name });
      b.title = label;
      b.setAttribute('aria-label', label);
    },
  );
  const r = dock.getBoundingClientRect();
  api.setSize(Math.ceil(r.width), Math.ceil(r.height));
}

function labels() {
  menu.title = t('dock.menu');
  menu.setAttribute('aria-label', menu.title);
  grip.title = t('dock.move');
}

grip.addEventListener('pointerdown', (e) => {
  if (e.button !== 0) return;
  grip.setPointerCapture(e.pointerId);
  api.startMove();
  const end = () => {
    api.endDrag();
    grip.removeEventListener('pointerup', end);
    grip.removeEventListener('lostpointercapture', end);
  };
  grip.addEventListener('pointerup', end);
  grip.addEventListener('lostpointercapture', end);
});
menu.addEventListener('click', () => {
  const r = menu.getBoundingClientRect();
  api.showMenu({ x: r.left, y: r.bottom });
});

async function main() {
  const boot = await api.init();
  applyBoot(boot);
  grip.append(icon('grip', 14));
  menu.append(icon('more', 16));
  labels();
  render(boot.state);
  api.onState(render);
  api.onBoot((b) => {
    applyBoot(b);
    labels();
  });
}

main();
