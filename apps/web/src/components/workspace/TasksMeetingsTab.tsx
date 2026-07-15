"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { ListChecks, CalendarClock, Plus } from "lucide-react";
import { subscribeTasks, addTask, toggleTask, subscribeMeetings, scheduleMeeting } from "@/lib/firebase/workspace";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import type { CollabTask, CollabMeeting } from "@/lib/types";

export function TasksMeetingsTab({ projectId }: { projectId: string }) {
  const [tasks, setTasks] = useState<CollabTask[]>([]);
  const [meetings, setMeetings] = useState<CollabMeeting[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [newTask, setNewTask] = useState("");
  const [meetingTitle, setMeetingTitle] = useState("");
  const [meetingWhen, setMeetingWhen] = useState("");

  useEffect(() => {
    const unsubTasks = subscribeTasks(projectId, setTasks, (err) => setError(friendlyFirestoreError(err)));
    const unsubMeetings = subscribeMeetings(projectId, setMeetings, (err) => setError(friendlyFirestoreError(err)));
    return () => {
      unsubTasks();
      unsubMeetings();
    };
  }, [projectId]);

  async function handleAddTask(e: React.FormEvent) {
    e.preventDefault();
    if (!newTask.trim()) return;
    try {
      await addTask(projectId, newTask.trim());
      setNewTask("");
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    }
  }

  async function handleScheduleMeeting(e: React.FormEvent) {
    e.preventDefault();
    if (!meetingTitle.trim() || !meetingWhen.trim()) return;
    try {
      await scheduleMeeting(projectId, meetingTitle.trim(), meetingWhen.trim());
      setMeetingTitle("");
      setMeetingWhen("");
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    }
  }

  if (error) return <ErrorBanner message={error} />;

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <h3 className="flex items-center gap-1.5 font-display text-[14px] font-semibold text-text-primary">
          <ListChecks size={15} className="text-accent-teal" />
          Tasks
        </h3>
        <div className="mt-2 flex flex-col gap-1.5">
          {tasks.length === 0 && <p className="font-body text-[13px] text-text-muted">No tasks yet.</p>}
          {tasks.map((t, i) => (
            <motion.label
              key={t.id}
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ duration: 0.25, delay: i * 0.03 }}
              className="flex items-center gap-2.5 rounded-[8px] bg-surface-subtle px-3 py-2"
            >
              <input
                type="checkbox"
                checked={t.isCompleted}
                onChange={(e) =>
                  toggleTask(projectId, t.id, e.target.checked).catch((err) =>
                    setError(friendlyFirestoreError(err))
                  )
                }
                className="h-4 w-4 accent-accent-teal"
              />
              <span
                className={`font-body text-[13.5px] ${t.isCompleted ? "text-text-muted line-through" : "text-text-primary"}`}
              >
                {t.title}
              </span>
            </motion.label>
          ))}
        </div>
        <form onSubmit={handleAddTask} className="mt-2.5 flex gap-2">
          <Input placeholder="Add a task..." value={newTask} onChange={(e) => setNewTask(e.target.value)} />
          <Button type="submit" fullWidth={false} className="w-24 gap-1">
            <Plus size={14} />
            Add
          </Button>
        </form>
      </Card>

      <Card>
        <h3 className="flex items-center gap-1.5 font-display text-[14px] font-semibold text-text-primary">
          <CalendarClock size={15} className="text-accent-indigo" />
          Meetings
        </h3>
        <div className="mt-2 flex flex-col gap-1.5">
          {meetings.length === 0 && <p className="font-body text-[13px] text-text-muted">Nothing scheduled.</p>}
          {meetings.map((m, i) => (
            <motion.div
              key={m.id}
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ duration: 0.25, delay: i * 0.03 }}
              className="rounded-[8px] bg-surface-subtle px-3 py-2"
            >
              <p className="font-body text-[13.5px] font-medium text-text-primary">{m.title}</p>
              <p className="font-body text-[12px] text-text-secondary">{m.when}</p>
            </motion.div>
          ))}
        </div>
        <form onSubmit={handleScheduleMeeting} className="mt-2.5 flex flex-col gap-2 sm:flex-row">
          <Input placeholder="Meeting title" value={meetingTitle} onChange={(e) => setMeetingTitle(e.target.value)} />
          <Input placeholder="When (e.g. Fri 3pm)" value={meetingWhen} onChange={(e) => setMeetingWhen(e.target.value)} />
          <Button type="submit" fullWidth={false} className="w-32 shrink-0">
            Schedule
          </Button>
        </form>
      </Card>
    </div>
  );
}
