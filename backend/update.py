import os

path = 'app/services/pipeline_services.py'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace get_network_collaborators signature
target = 'async def get_network_collaborators(self, author_id: str, limit: int = 10, offset: int = 0, exclude_ids: List[str] = None) -> List[Dict[str, Any]]:'
replacement = 'async def get_network_collaborators(self, author_id: str, limit: int = 10, offset: int = 0, exclude_ids: List[str] = None, field: str = "") -> List[Dict[str, Any]]:'
content = content.replace(target, replacement)

# Replace doc ID
content = content.replace('doc = db.collection("network_collaborators").document(clean_id).get(timeout=2.0)',
                          'doc = db.collection("network_collaborators").document(f"{clean_id}_{field}").get(timeout=2.0)')
content = content.replace('db.collection("network_collaborators").document(clean_id).set({',
                          'db.collection("network_collaborators").document(f"{clean_id}_{field}").set({')

# The logic to handle seed fallback
seed_logic = """
        # --- Seed Fallback & Field Filtering Logic ---
        # If the requested author has no works in the requested field, or is not found, 
        # we try to find a seed author in that field.
        seed_id = clean_id
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                # Check if the author has any works in this field
                filter_q = f"authorships.author.id:{clean_id}"
                if field and field != "All Fields":
                    filter_q += f",concepts.display_name:{field}"
                
                res = await client.get(f"{self.openalex_base}/works", params={"filter": filter_q, "per_page": 1}, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    if data.get("meta", {}).get("count", 0) == 0 and field and field != "All Fields":
                        # Fallback: search for top author in this field
                        fallback_res = await client.get(
                            f"{self.openalex_base}/authors",
                            params={"search": field, "sort": "cited_by_count:desc", "per_page": 1},
                            headers=self.headers
                        )
                        if fallback_res.status_code == 200 and fallback_res.json().get("results"):
                            seed_id = fallback_res.json()["results"][0]["id"].split("/")[-1]
                            print(f"Fallback seed author for field {field}: {seed_id}", flush=True)
        except Exception as e:
            print(f"Error checking seed author: {e}", flush=True)
            
        clean_id = seed_id
        # --- End Seed Fallback ---
"""

# Find where to insert seed logic
# We insert it right after `exclude_set.add(clean_id)`
insert_target = """
        exclude_set = set(exclude_ids) if exclude_ids else set()
        exclude_set.add(clean_id)
"""
content = content.replace(insert_target, insert_target + seed_logic)

# Filter for works query
# Replace f"authorships.author.id:{clean_id}" with dynamic filter
works_target = 'params={"filter": f"authorships.author.id:{clean_id}", "per_page": 30},'
works_replacement = 'params={"filter": f"authorships.author.id:{clean_id}" + (f",concepts.display_name:{field}" if field and field != "All Fields" else ""), "per_page": 30},'
content = content.replace(works_target, works_replacement)

works2_target = 'params={"filter": f"authorships.author.id:{d1_clean_id}", "per_page": 10},'
works2_replacement = 'params={"filter": f"authorships.author.id:{d1_clean_id}" + (f",concepts.display_name:{field}" if field and field != "All Fields" else ""), "per_page": 10},'
content = content.replace(works2_target, works2_replacement)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Updated pipeline_services.py')
