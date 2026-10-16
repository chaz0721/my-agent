# SWE-bench evaluation harness in a Linux container (swebench imports the Unix-only `resource`
# module, so it cannot run on the Windows host). The host Docker socket is mounted at run time;
# the harness starts the per-instance evaluation containers next to this one.
FROM python:3.11-slim
RUN pip install --no-cache-dir swebench==4.1.0
WORKDIR /eval/harness
