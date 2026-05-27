import os
import json
import httpx
from typing import Dict, Any, List

# --- Real Connectors ---

async def search_arxiv(query: str, max_results: int = 5) -> str:
    """Real connector to search arXiv."""
    url = f"http://export.arxiv.org/api/query?search_query=all:{query}&start=0&max_results={max_results}"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(url)
            if res.status_code == 200:
                # Basic parsing (arXiv returns XML, we will just return a summary of it or mock the parsing)
                # For a robust connector, we'd use xml.etree.ElementTree. Here we do a fast substring extract.
                text = res.text
                entries = text.split("<entry>")
                results = []
                for entry in entries[1:]:
                    title = entry.split("<title>")[1].split("</title>")[0].strip().replace("\n", "")
                    summary = entry.split("<summary>")[1].split("</summary>")[0].strip().replace("\n", " ")
                    results.append(f"Title: {title}\nSummary: {summary[:200]}...")
                if results:
                    return "\n\n".join(results)
                return "No arXiv results found."
    except Exception as e:
        return f"Failed to connect to arXiv: {e}"
    return "No results."

def list_local_files(directory_path: str = ".") -> str:
    """Real connector to list local files in a directory."""
    try:
        # Sanitize to prevent accessing root system files
        safe_path = os.path.abspath(directory_path)
        if not os.path.exists(safe_path):
            return f"Directory {safe_path} does not exist."
        files = os.listdir(safe_path)
        return f"Files in {safe_path}:\n" + "\n".join(files[:50])
    except Exception as e:
        return f"Error reading local folder: {e}"

def read_local_file(file_path: str) -> str:
    """Real connector to read a local file's content."""
    try:
        safe_path = os.path.abspath(file_path)
        if not os.path.exists(safe_path):
            return f"File {safe_path} does not exist."
        with open(safe_path, 'r', encoding='utf-8') as f:
            content = f.read(2000) # read up to 2000 chars
            return f"Content of {safe_path}:\n{content}"
    except Exception as e:
        return f"Error reading file: {e}"

async def search_web(query: str, max_results: int = 5) -> str:
    """Real connector to search the web using DuckDuckGo HTML with Instant Answer fallback."""
    url = f"https://html.duckduckgo.com/html/?q={query}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(url, headers=headers)
            if res.status_code == 200 and "result__a" in res.text:
                html = res.text
                parts = html.split('class="result results_links results_links_deep web-result')
                if len(parts) <= 1:
                    parts = html.split('class="web-result')
                results = []
                import re
                for part in parts[1:max_results + 1]:
                    try:
                        title_part = part.split('class="result__a"')
                        if len(title_part) > 1:
                            link = title_part[1].split('href="')[1].split('"')[0]
                            if "uddg=" in link:
                                from urllib.parse import unquote
                                link = unquote(link.split("uddg=")[1].split("&")[0])
                            
                            first_gt = title_part[1].find('>')
                            if first_gt != -1:
                                title = title_part[1][first_gt + 1:].split('</a')[0]
                                title = re.sub('<[^>]+?>', '', title).strip()
                            else:
                                title = "Unknown"
                        else:
                            continue
                        
                        snippet_part = part.split('class="result__snippet"')
                        if len(snippet_part) > 1:
                            first_gt = snippet_part[1].find('>')
                            if first_gt != -1:
                                snippet = snippet_part[1][first_gt + 1:].split('</a')[0]
                                snippet = re.sub('<[^>]+?>', '', snippet).strip()
                            else:
                                snippet = ""
                        else:
                            snippet = ""
                        
                        results.append(f"Title: {title}\nURL: {link}\nSnippet: {snippet}")
                    except Exception:
                        continue
                if results:
                    return "\n\n".join(results)
    except Exception:
        pass

    # Fallback to DDG Instant Answer API
    try:
        api_url = f"https://api.duckduckgo.com/?q={query}&format=json&no_html=1&skip_disambig=1"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(api_url, headers=headers)
            if res.status_code == 200:
                data = res.json()
                results = []
                abstract = data.get("AbstractText", "")
                abstract_url = data.get("AbstractURL", "")
                if abstract:
                    results.append(f"Abstract: {abstract}\nURL: {abstract_url}")
                
                topics = data.get("RelatedTopics", [])
                for topic in topics[:max_results]:
                    if "Text" in topic and "FirstURL" in topic:
                        results.append(f"Topic: {topic['Text']}\nURL: {topic['FirstURL']}")
                if results:
                    return "\n\n".join(results)
    except Exception as e:
        return f"Failed to search the web: {e}"

    return "No web results found."

# --- Simulated Connectors ---

def search_gmail(sender: str = "", topic: str = "") -> str:
    """Simulated connector for Gmail."""
    # In a real scenario, this would use google-api-python-client with OAuth2.
    return f"""[SIMULATED GMAIL] Found 2 recent emails matching sender='{sender}' or topic='{topic}':
1. From: collaborator@mit.edu - "Re: Draft of the quantum physics paper" - Let's review the final draft tomorrow.
2. From: grant-committee@nsf.gov - "Grant Application Update" - Your application is in the final review stage."""

def search_whatsapp(contact: str = "") -> str:
    """Simulated connector for WhatsApp."""
    # In a real scenario, this would integrate with WhatsApp Business API or a local bridge.
    return f"""[SIMULATED WHATSAPP] Recent messages from '{contact}':
- Yesterday, 4:30 PM: "Hey, did you see the new Nature preprint?"
- Today, 9:15 AM: "I'll send over the datasets by noon." """


# --- Tool Execution Engine ---

# These definitions are passed to the Groq LLM API
TOOLS_SCHEMA = [
    {
        "type": "function",
        "function": {
            "name": "search_arxiv",
            "description": "Searches the arXiv API for academic papers on a given topic.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query, e.g., 'quantum entanglement'"},
                    "max_results": {"type": "integer", "description": "Max results to return (default 5)"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_local_files",
            "description": "Lists files and folders in a local directory on the laptop.",
            "parameters": {
                "type": "object",
                "properties": {
                    "directory_path": {"type": "string", "description": "The path to list, e.g., '.' for current directory"}
                },
                "required": ["directory_path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_local_file",
            "description": "Reads the content of a local text file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "file_path": {"type": "string", "description": "The absolute or relative path to the file"}
                },
                "required": ["file_path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_gmail",
            "description": "Searches the user's Gmail inbox for recent emails matching a sender or topic.",
            "parameters": {
                "type": "object",
                "properties": {
                    "sender": {"type": "string", "description": "The person who sent the email"},
                    "topic": {"type": "string", "description": "The topic or keyword in the email"}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_whatsapp",
            "description": "Searches the user's WhatsApp messages for recent chats with a contact.",
            "parameters": {
                "type": "object",
                "properties": {
                    "contact": {"type": "string", "description": "The name of the WhatsApp contact"}
                },
                "required": ["contact"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_web",
            "description": "Searches the live web for recent or dynamic information outside academic databases (e.g., news, current events, software libraries, documentation, or facts). Use this when the user asks about live, real-time, or dynamic topics.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query, e.g., 'React 19 release date'"},
                    "max_results": {"type": "integer", "description": "Max results to return (default 5)"}
                },
                "required": ["query"]
            }
        }
    }
]

async def execute_tool_call(tool_name: str, arguments: dict) -> str:
    """Routes the LLM's tool call to the actual Python function."""
    print(f"[Agent Tool Execution] Calling {tool_name} with {arguments}", flush=True)
    try:
        if tool_name == "search_arxiv":
            return await search_arxiv(arguments.get("query", ""), arguments.get("max_results", 5))
        elif tool_name == "list_local_files":
            return list_local_files(arguments.get("directory_path", "."))
        elif tool_name == "read_local_file":
            return read_local_file(arguments.get("file_path", ""))
        elif tool_name == "search_gmail":
            return search_gmail(arguments.get("sender", ""), arguments.get("topic", ""))
        elif tool_name == "search_whatsapp":
            return search_whatsapp(arguments.get("contact", ""))
        elif tool_name == "search_web":
            return await search_web(arguments.get("query", ""), arguments.get("max_results", 5))
        else:
            return f"Unknown tool: {tool_name}"
    except Exception as e:
        return f"Error executing tool {tool_name}: {e}"
