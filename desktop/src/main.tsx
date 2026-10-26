import { createRoot } from 'react-dom/client';
import App from './App';
import './styles/tokens.css';
import './styles/global.css';
import './styles/app.css';
import './styles/timetable.css';

async function bootstrap() {
  const params = new URLSearchParams(window.location.search);
  if (params.has('reset')) {
    const [{ clearLocalData }, { del }] = await Promise.all([
      import('./core/storage'),
      import('idb-keyval'),
    ]);
    clearLocalData();
    await del('miulist.snapshotHandle.v1');
    params.delete('reset');
    const query = params.toString();
    window.location.replace(window.location.pathname + (query ? `?${query}` : ''));
    return;
  }
  const root = document.getElementById('root');
  if (!root) throw new Error('找不到 #root');
  createRoot(root).render(<App />);
}

void bootstrap();
