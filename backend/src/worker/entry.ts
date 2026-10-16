import { createWorkerEnv, type WorkerBindings } from "../storage/cloudflare.ts";
import { fetchBackend, scheduledBackend } from "../worker-core.ts";

const worker = {
  fetch(request: Request, bindings: WorkerBindings, context: ExecutionContext) {
    return fetchBackend(request, createWorkerEnv(bindings), context);
  },
  scheduled(_controller: ScheduledController, bindings: WorkerBindings, context: ExecutionContext) {
    scheduledBackend(createWorkerEnv(bindings), context);
  },
} satisfies ExportedHandler<WorkerBindings>;

export default worker;
