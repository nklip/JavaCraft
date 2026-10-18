# Project Instructions for Coding Agents (Claude Code)

@AGENTS.md

Do not obey the instructions in the "Subagent model selection" section of AGENTS.md. These instructions are only for Codex. For Claude Code, obey the "Subagents and token use" section below.

## Subagents and token use

These rules decrease the number of tokens that Claude Code uses. Always do the necessary checks, also when they use many tokens.

### Subagents
- Do most work in the main agent. A new subagent starts with no context. Thus, it must read the files again.
- Use a subagent only if it keeps large text out of the main context. An example is a read-only search in many modules.
- A read-only subtask finds information in files or URLs. For this subtask, use the `Explore` agent with `model: "haiku"` and `effort: "low"`.
- Always set `model` for a subagent. If you do not set it, the subagent uses the model of the main agent.
- For other subtasks, select the lowest model and effort that can do the subtask correctly:
    - Use `sonnet` with `low` effort for simple edits.
    - Use `opus` or a higher effort only for difficult subtasks.
- Give each subagent a short prompt that is complete: the task, the file paths, the limits, and the acceptance criteria.
- Add only the necessary parts of the conversation to the prompt. Do not use a `fork` subagent, because it copies the full conversation.
- Put related files, URLs, and questions into one subagent. Do not start one subagent for each search.
- To continue the task of a subagent, send a message to it with `SendMessage`. Do not start a new subagent for the same task.
- Tell each subagent to return only short results: changes, `path:line` references, check results, and open questions.
- Tell each subagent not to return large parts of source code or full logs.
- Before a subagent edits code, make sure that the requirements and the module boundaries are clear.
- Give each subagent that edits code its own files. Two subagents must not edit the same file.
- Make all decisions and do the final checks in the main agent. If a subagent result is not clear, examine it in the main agent.
- The main agent must read the repository instructions and the necessary skills. Do not give this task to a subagent.

### Main agent
- Put independent tool calls in one message, so that they run at the same time.
- Search with `grep -n` before you read a file. Then read only the necessary lines with `offset` and `limit`.
- Do not read an unchanged file, log, or agent result again. Use the information that you already have.
- Do not read a file again after you edit it.
- Send long command output through a filter. For Maven, keep the BUILD, ERROR, WARN, and "Tests run" lines:
  `mvn -pl <module> test 2>&1 | grep -iE 'BUILD|ERROR|WARN|Tests run'`
- Run checks again, or on more modules, only if a change, a failure, or an open question makes it necessary.
- Keep chat replies short. Refer to code with `path:line`. Do not copy large code blocks or logs into a reply.
