# Regenerates app/src/main/assets/ime/dict/bigrams.tsv, unverified.txt and valid.txt.
# Usage: python3 utils/gen_dict_assets.py <symspellpy package dir> app/src/main/assets/ime/dict/data.json app/src/main/assets/ime/dict
# The symspellpy package dir comes from: pip download symspellpy --no-deps, then unzip the wheel.
#
# bigrams.tsv line format: word<TAB>unseen<TAB>marginal<TAB>follower:ppm,follower:ppm,...
#   follower:ppm  P(follower | word) in parts per million, most likely first.
#   unseen        The highest P(x | word) in parts per million that a pair missing from the list could have, since
#                 the source only lists pairs above a fixed count. Lets the keyboard tell "never seen after this
#                 word" apart from "too rare to know".
#   marginal      P(word) as the second word of any pair, in parts per billion. 0 if it never follows anything.
# Comparing P(follower | word) to the follower's marginal tells whether a word fits after another one.
import collections, json, re, sys

symspell_dir, data_json, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
word_re = re.compile(r"^[a-z]+$")

data = json.load(open(data_json))
dictionary = {w.lower() for w in data}

unigrams = {}
for line in open(f"{symspell_dir}/frequency_dictionary_en_82_765.txt"):
    w, c = line.split()
    unigrams[w] = int(c)

# Totals come from every pair in the source, so probabilities stay consistent even for pairs dropped below
row_total = collections.Counter()
col_total = collections.Counter()
min_count = None
followers = collections.defaultdict(list)
for line in open(f"{symspell_dir}/frequency_bigramdictionary_en_243_342.txt"):
    a, b, c = line.split()
    c = int(c)
    row_total[a] += c
    col_total[b] += c
    min_count = c if min_count is None else min(min_count, c)
    if a in dictionary and b in dictionary and word_re.match(a) and word_re.match(b):
        followers[a].append((b, c))
all_pairs = sum(row_total.values())

words = sorted(w for w in dictionary if word_re.match(w) and (w in followers or col_total[w] > 0))
pairs = 0
with open(f"{out_dir}/bigrams.tsv", "w") as f:
    f.write("# Next word statistics: word<TAB>unseen ppm<TAB>marginal ppb<TAB>follower:ppm,...\n")
    f.write("# See utils/gen_dict_assets.py for the meaning of each column.\n")
    f.write("# Derived from the SymSpell English bigram and frequency dictionaries\n")
    f.write("# (https://github.com/wolfgarbe/SymSpell, MIT), based on Google Books Ngram data (CC BY 3.0).\n")
    for a in words:
        total = row_total[a]
        if total > 0:
            unseen = 1e6 * min_count / total
            unseen_text = f"{unseen:.3g}" if unseen < 1e6 else "1000000"
        else:
            unseen_text = "0"
        marginal = round(1e9 * col_total[a] / all_pairs)
        cells = []
        for b, c in sorted(followers.get(a, []), key=lambda x: -x[1]):
            cells.append(f"{b}:{max(1, round(1e6 * c / total))}")
        pairs += len(cells)
        f.write(f"{a}\t{unseen_text}\t{marginal}\t{','.join(cells)}\n")

unverified = sorted(w for w in dictionary if word_re.match(w) and w not in unigrams)
with open(f"{out_dir}/unverified.txt", "w") as f:
    f.write("# Dictionary words not found in the SCOWL based SymSpell English word list (names, slang, noise).\n")
    f.write("# Used to let autocorrect fix typos that happen to match one of them.\n")
    for w in unverified:
        f.write(w + "\n")

# Real English words (SCOWL, the word list behind common spell checkers) that the keyboard's dictionary lacks, such
# as "wagged". They are never suggested, but autocorrect leaves them alone instead of "fixing" them.
valid = sorted(w for w in unigrams if word_re.match(w) and w not in dictionary)
with open(f"{out_dir}/valid.txt", "w") as f:
    f.write("# Real words missing from data.json, from the SCOWL based SymSpell English word list.\n")
    f.write("# Never suggested, only protected from autocorrect.\n")
    for w in valid:
        f.write(w + "\n")

print("words", len(words), "pairs", pairs, "unverified", len(unverified), "valid", len(valid))
