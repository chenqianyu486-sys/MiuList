import { useEffect, useState, type FormEvent } from 'react';
import { CalendarClock, ListPlus, Star } from 'lucide-react';
import { toDueInput } from '../core/format';
import { useAppStore } from '../core/store';
import { addTask, updateTask } from '../core/sync';
import { taskTypeOf, taskTypeOptions } from '../core/taskTypes';
import type { TaskDraft } from '../core/types';
import { Sheet } from './Sheet';
import { Button, cx } from './ui';

export function TaskEditor() {
  const open = useAppStore((s) => s.editorOpen);
  const task = useAppStore((s) => s.editingTask);
  const snapshot = useAppStore((s) => s.snapshot);
  const close = useAppStore((s) => s.closeTaskEditor);
  const options = taskTypeOptions(snapshot.taskTypes);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [typeName, setTypeName] = useState('');
  const [due, setDue] = useState('');
  const [starred, setStarred] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (task) {
      setTitle(task.title || '');
      setDescription(task.description || '');
      setTypeName(taskTypeOf(task, snapshot.taskTypes).name || '');
      setDue(toDueInput(task));
      setStarred(task.priority === 'STAR');
    } else {
      setTitle('');
      setDescription('');
      setTypeName('');
      setDue('');
      setStarred(false);
    }
    // 只在打开 / 换一条待办时重置；同步回来的 taskTypes 不应覆盖正在编辑的草稿。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, task?.syncUid]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const draft: TaskDraft = {
      title,
      description,
      taskTypeName: typeName,
      dueLocal: due,
      starred,
    };
    const result = task ? updateTask(task.syncUid, draft) : addTask(draft);
    if (result) close();
  };

  return (
    <Sheet
      open={open}
      onClose={close}
      title={task ? '编辑待办' : '新建待办'}
      subtitle={
        task
          ? '修改会先保存在本机，连上手机后自动同步。'
          : '先写下来；连上手机后会自动同步过去。'
      }
    >
      <form className="editor-form" onSubmit={submit}>
        <label className="field">
          <span>标题</span>
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="要做什么？"
            autoFocus
            required
            maxLength={120}
          />
        </label>

        <div className="editor-grid">
          <label className="field">
            <span>类别</span>
            <select value={typeName} onChange={(event) => setTypeName(event.target.value)}>
              <option value="">不分类</option>
              {options.map((option) => (
                <option key={option.name || option.label} value={option.name}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>
              <CalendarClock size={13} /> 截止时间
            </span>
            <input
              type="datetime-local"
              value={due}
              onChange={(event) => setDue(event.target.value)}
            />
          </label>
        </div>

        <label className="field">
          <span>备注</span>
          <textarea
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="可选的补充说明"
            rows={3}
          />
        </label>

        <button
          type="button"
          className={cx('star-toggle', starred && 'is-on')}
          aria-pressed={starred}
          onClick={() => setStarred((value) => !value)}
        >
          <Star size={17} fill={starred ? 'currentColor' : 'none'} />
          <span>{starred ? '已星标' : '加星标'}</span>
        </button>

        <p className="field-hint">
          截止时间按手机所在时区保存；不填就是不设截止时间。编辑已有待办时，修改会覆盖手机上的对应条目。
        </p>

        <div className="editor-actions">
          <Button variant="ghost" onClick={close}>
            取消
          </Button>
          <Button variant="primary" type="submit" icon={ListPlus} disabled={!title.trim()}>
            {task ? '保存修改' : '加入清单'}
          </Button>
        </div>
      </form>
    </Sheet>
  );
}
