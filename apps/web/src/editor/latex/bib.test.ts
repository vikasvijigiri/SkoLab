import { describe, expect, it } from "vitest";
import { bibitems, describe as describeEntry, parseBib, shortAuthors } from "./bib";

const BIB = String.raw`@string{prl = "Phys. Rev. Lett."}
% a comment
@article{curie1898,
  author = {Curie, Pierre and Curie, Marie and B{\'e}mont, Gustave},
  title = {Sur une substance nouvelle {radio-active}},
  year = 1898,
}
@book{knuth84, title="The {\TeX}book", author="Donald E. Knuth", year="1984"}
@misc{noauthor, date = {2020-05-01}}
@inproceedings{broken
`;

describe("bibliographies", () => {
  it("reads entries from a .bib file", () => {
    const entries = parseBib(BIB, "refs.bib");
    expect(entries.map((entry) => [entry.key, entry.type, entry.authors, entry.year, entry.title, entry.line])).toEqual([
      ["curie1898", "article", "Curie et al.", "1898", "Sur une substance nouvelle radio-active", 3],
      ["knuth84", "book", "Knuth", "1984", "The book", 8],
      ["noauthor", "misc", "", "2020", "", 9],
    ]);
    expect(entries[0]?.file).toBe("refs.bib");
  });

  it("shortens author lists", () => {
    expect(shortAuthors("")).toBe("");
    expect(shortAuthors("Ada Lovelace")).toBe("Lovelace");
    expect(shortAuthors("Lovelace, Ada and Babbage, Charles")).toBe("Lovelace and Babbage");
  });

  it("reads \\bibitem entries and describes entries in a line", () => {
    const items = bibitems("\\begin{thebibliography}{9}\n\\bibitem[A]{a1} A. Author, Some title.\n% \\bibitem{gone}\n\\bibitem{ }\n\\end{thebibliography}");
    expect(items.map((item) => [item.key, item.title, item.line])).toEqual([["a1", "A. Author, Some title.", 2]]);
    const [first] = parseBib(BIB);
    if (!first) throw new Error("no entry");
    expect(describeEntry(first)).toBe("Curie et al. (1898) · Sur une substance nouvelle radio-active");
    expect(describeEntry({ key: "k", type: "misc", title: "", authors: "", year: "", file: null, line: 1 })).toBe("misc");
  });
});
