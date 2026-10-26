import { useEffect } from 'react';
import { Background } from './components/Background';
import { DetailSheet } from './components/DetailSheet';
import { QrScanner } from './components/QrScanner';
import { Shell } from './components/Shell';
import { Splash } from './components/Splash';
import { SyncSheet } from './components/SyncSheet';
import { TaskEditor } from './components/TaskEditor';
import { Toast } from './components/Toast';
import { snapshotHasData, useAppStore } from './core/store';
import { boot } from './core/sync';
import { Welcome } from './views/Welcome';

export default function App() {
  const booted = useAppStore((s) => s.booted);
  const hasData = useAppStore((s) => snapshotHasData(s.snapshot));
  const secret = useAppStore((s) => s.secret);

  useEffect(() => {
    void boot();
  }, []);

  return (
    <div className="app-root">
      <Background />
      {!booted ? <Splash /> : hasData || secret ? <Shell /> : <Welcome />}
      <SyncSheet />
      <TaskEditor />
      <DetailSheet />
      <QrScanner />
      <Toast />
    </div>
  );
}
