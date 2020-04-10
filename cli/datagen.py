import argparse
import math
import multiprocessing
import os
import pickle
import shutil
import sys
from collections import Counter
from itertools import islice

from cli import CLIArgsException, StatefulActivity, activitystep, preprocess
from cli.mmt import collect_parallel_files, MMT_HOME_DIR, MMT_JAR
from mmt.factorencoder import FactorDictionary
from mmt.textencoder import SubwordDictionary


def _pool_initializer(vocab_path):
    global bpe_vocab
    bpe_vocab = SubwordDictionary.load(vocab_path)


def _apply_bpe(entry):
    global bpe_vocab

    if len(entry) == 2:
        src_line, tgt_line = entry
        factor_line = None
    elif len(entry) == 3:
        src_line, tgt_line, factor_line = entry
    else:
        raise Exception('wrong entry format: expected 2-tuple or 3-tuple, foudn {}-tuple' + str(len(entry)))

    src_line, tgt_line = src_line.strip(), tgt_line.strip()
    if factor_line is not None:
        factor_line = factor_line.strip()

    src_tokens, factor_tokens = bpe_vocab.tokenize_with_factors(src_line, factor_line)
    tgt_tokens = bpe_vocab.tokenize(tgt_line)

    if len(src_tokens) == 0 or len(tgt_tokens) == 0:
        return None, None, None, 0, 0
    else:
        return ' '.join(src_tokens) + '\n', \
               ' '.join(tgt_tokens) + '\n', \
               ' '.join(factor_tokens) + '\n', \
               len(src_tokens), \
               len(tgt_tokens)


class _Sequence(object):
    def __init__(self) -> None:
        self._sum = 0
        self._sum2 = 0
        self._count = 0
        self._counter = Counter()

    def add(self, value):
        self._sum += value
        self._sum2 += value * value
        self._count += 1

        value = int(value * 10) / 10.
        self._counter[value] += 1

    @property
    def modal_value(self):
        return self._counter.most_common(1)[0][0]

    @property
    def avg(self):
        return self._sum / self._count

    @property
    def std_dev(self):
        return math.sqrt((self._sum2 / self._count) - (self.avg * self.avg))

    def __len__(self):
        return self._count


class DatagenActivity(StatefulActivity):
    DEFAULT_VALIDATION_LINES = 3000

    def __init__(self, args, extra_argv=None, wdir=None, log_file=None, start_step=None, delete_on_exit=True):
        super().__init__(args, extra_argv, wdir, log_file, start_step, delete_on_exit)

        self._langs = [tuple(lp.split(':')) for lp in args.lang_pairs.split(',')]
        self._mono_pairs = [tuple(lp.split(':')) for lp in set([('%s:%s' % tuple(sorted(p))) for p in self._langs])]
        self._target_langs = set([p[1] for p in self._langs])

        self._task = args.task
        self._with_factors = self.args.with_factors

        self._input_paths = self.args.input_paths

        if isinstance(args.input_paths, str):
            self._input_paths = [self._input_paths]

    @activitystep('Training BPE model')
    def bpe_create(self):

        os.makedirs(self.args.output_path, exist_ok=True)
        self.state.vocab = os.path.join(self.args.output_path, 'model.vcb')

        # Copy existing model if specified
        if self.args.vocabulary_path is not None:
            shutil.copyfile(self.args.vocabulary_path, self.state.vocab)
            return

        # Create custom tokens list
        custom_tokens = [('${DNT%d}' % i) for i in range(10)]

        if len(self._target_langs) > 1:
            custom_tokens = [SubwordDictionary.language_tag(l) for l in self._target_langs] + custom_tokens

        # Collect all training files
        all_files = []

        for path in self._input_paths:
            for src_lang, tgt_lang in self._mono_pairs:
                lang_dir = '%s__%s' % (src_lang, tgt_lang)
                train_path = os.path.join(path, lang_dir, 'train')
                dev_path = os.path.join(path, lang_dir, 'dev')

                all_src, all_tgt, _ = collect_parallel_files(src_lang, tgt_lang, [train_path, dev_path])

                all_files.extend(all_src)
                all_files.extend(all_tgt)

        # Build SubwordDictionary
        builder = SubwordDictionary.Factory(self.args.voc_size,
                                            vocab_threads=self.args.threads,
                                            custom_tokens=custom_tokens,
                                            padding_factor=8,
                                            count_threshold=self.args.count_threshold)
        dictionary = builder.build(all_files, tmp_path=self.wdir('bpe_temp'))
        dictionary.save(self.state.vocab)

    def _bpe_encode_files(self, pool, src_lang, tgt_lang,
                          in_src_files, in_tgt_files, in_factor_files, out_src_file_obj, out_tgt_file_obj,
                          out_factor_file_obj):
        # TODO: removed the birectional generation: need to decide what to do

        # TODO: we could skip the creation of the bpe for the factors if factors are not required

        src_prefix = None
        if len(self._target_langs) > 1:
            src_prefix = SubwordDictionary.language_tag(tgt_lang) + '_ '

        batch_size = (multiprocessing.cpu_count() or 1) * 100

        fwd_seq, bwd_seq = _Sequence(), _Sequence()

        for in_src_file, in_tgt_file, in_factor_file in zip(in_src_files, in_tgt_files, in_factor_files):
            if in_factor_file is not None:
                with open(in_src_file, 'r', encoding='utf-8') as in_src_file_obj, \
                        open(in_tgt_file, 'r', encoding='utf-8') as in_tgt_file_obj, \
                        open(in_factor_file, 'r', encoding='utf-8') as in_factor_file_obj:
                    for batch in iter(lambda: tuple(
                            islice(zip(in_src_file_obj, in_tgt_file_obj, in_factor_file_obj), batch_size)), ()):
                        for src_line, tgt_line, factor_line, src_len, tgt_len in pool.map(_apply_bpe, batch):
                            if src_line is None or tgt_line is None:
                                continue

                            s2t_rate, t2s_rate = tgt_len / src_len, src_len / tgt_len
                            fwd_seq.add(s2t_rate)
                            bwd_seq.add(t2s_rate)

                            if src_prefix is not None:
                                out_src_file_obj.write(src_prefix)
                                out_factor_file_obj.write(FactorDictionary.default_factor())
                            out_src_file_obj.write(src_line)
                            out_tgt_file_obj.write(tgt_line)
                            out_factor_file_obj.write(factor_line)
            else:
                with open(in_src_file, 'r', encoding='utf-8') as in_src_file_obj, \
                        open(in_tgt_file, 'r', encoding='utf-8') as in_tgt_file_obj:
                    for batch in iter(lambda: tuple(islice(zip(in_src_file_obj, in_tgt_file_obj), batch_size)), ()):
                        for src_line, tgt_line, factor_line, src_len, tgt_len in pool.map(_apply_bpe, batch):
                            if src_line is None or tgt_line is None:
                                continue

                            s2t_rate, t2s_rate = tgt_len / src_len, src_len / tgt_len
                            fwd_seq.add(s2t_rate)
                            bwd_seq.add(t2s_rate)

                            if src_prefix is not None:
                                out_src_file_obj.write(src_prefix)
                                out_factor_file_obj.write(FactorDictionary.default_factor())
                            out_src_file_obj.write(src_line)
                            out_tgt_file_obj.write(tgt_line)
                            out_factor_file_obj.write(factor_line)

        return fwd_seq, bwd_seq

    @activitystep('Encoding training corpora')
    def bpe_encode(self):
        self.state.encoded_corpora = out_dir = self.wdir('encoded_corpora')

        train_sl = os.path.join(out_dir, 'train.sl')
        train_tl = os.path.join(out_dir, 'train.tl')
        train_factor = os.path.join(out_dir, 'train.factor')
        dev_sl = os.path.join(out_dir, 'dev.sl')
        dev_tl = os.path.join(out_dir, 'dev.tl')
        dev_factor = os.path.join(out_dir, 'dev.factor')

        covered_langs = set()
        decode_lengths = {}

        with open(train_sl, 'w', encoding='utf-8') as train_sl_obj, \
                open(train_tl, 'w', encoding='utf-8') as train_tl_obj, \
                open(train_factor, 'w', encoding='utf-8') as train_factor_obj, \
                open(dev_sl, 'w', encoding='utf-8') as dev_sl_obj, \
                open(dev_tl, 'w', encoding='utf-8') as dev_tl_obj, \
                open(dev_factor, 'w', encoding='utf-8') as dev_factor_obj:
            with multiprocessing.Pool(initializer=_pool_initializer, initargs=(self.state.vocab,)) as pool:
                for src_lang, tgt_lang in self._langs:

                    lang_dir = '%s__%s' % tuple(sorted([src_lang, tgt_lang]))
                    if lang_dir in covered_langs:
                        continue
                    covered_langs.add(lang_dir)

                    for path in self._input_paths:
                        train_path = os.path.join(path, lang_dir, 'train')
                        dev_path = os.path.join(path, lang_dir, 'dev')

                        train_src_files, train_tgt_files, train_factor_files = collect_parallel_files(src_lang,
                                                                                                      tgt_lang,
                                                                                                      train_path)
                        dev_src_files, dev_tgt_files, dev_factor_files = collect_parallel_files(src_lang,
                                                                                                tgt_lang,
                                                                                                dev_path)
                        fwd_seq, bwd_seq = self._bpe_encode_files(pool, src_lang, tgt_lang,
                                                                  train_src_files, train_tgt_files, train_factor_files,
                                                                  train_sl_obj, train_tl_obj, train_factor_obj)
                        self._bpe_encode_files(pool, src_lang, tgt_lang,
                                               dev_src_files, dev_tgt_files, dev_factor_files,
                                               dev_sl_obj, dev_tl_obj, dev_factor_obj)

                        decode_lengths['%s__%s' % (src_lang, tgt_lang)] = (fwd_seq.modal_value, fwd_seq.std_dev)
                        decode_lengths['%s__%s' % (tgt_lang, src_lang)] = (bwd_seq.modal_value, bwd_seq.std_dev)

        os.makedirs(self.args.output_path, exist_ok=True)
        with open(os.path.join(self.args.output_path, 'decode_lengths.bin'), 'wb') as f:
            pickle.dump(decode_lengths, f)

    @activitystep('Generating binary data')
    def datagen(self):
        os.makedirs(self.args.output_path, exist_ok=True)

        train_pref = os.path.join(self.state.encoded_corpora, 'train')
        valid_pref = os.path.join(self.state.encoded_corpora, 'dev')

        key = 'PYTHONPATH'
        python_paths = os.environ[key].split(':') if key in os.environ else []
        python_paths.append(MMT_HOME_DIR)
        python_paths.append(MMT_JAR)
        os.environ[key] = ":".join(sorted(python_paths))

        sys.path.insert(0, MMT_JAR)
        cpu_count = multiprocessing.cpu_count()  # TODO: it seems that it does not work when workers are larger than 1

        args = ['--source-lang', 'sl', '--target-lang', 'tl',
                '--trainpref', train_pref, '--validpref', valid_pref,
                '--destdir', self.args.output_path, '--workers', str(cpu_count),
                '--srcdict', self.state.vocab, '--joined-dictionary', '--dataset-impl', 'mmap']
        if self._with_factors:
            args.append('--with-factors')
        if self._task is not None:
            args.extend(['--task', self._task])

        preprocess.main(args)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description='Generate archives for neural training', prog='mmt datagen')
    parser.add_argument('lang_pairs', metavar='LANGUAGE_PAIRS',
                        help='the language pair list encoded as <ls1>:<t1>[,<lsn>:<ltn>] (i.e. en:it,it:en,en:fr)')
    parser.add_argument('output_path', metavar='OUTPUT', help='the destination folder')
    parser.add_argument('input_paths', nargs='+', metavar='INPUT_PATHS', help='the paths to the training corpora')
    parser.add_argument('-w', '--working-dir', metavar='WORKING_DIR', dest='wdir', default=None,
                        help='the working directory for temporary files (default is os temp folder)')
    parser.add_argument('-d', '--debug', action='store_true', dest='debug', default=False,
                        help='prevents temporary files to be removed after execution')
    parser.add_argument('-s', '--voc-size', dest='voc_size', default=32768, type=int,
                        help='the vocabulary size to use (default is 32768)')
    parser.add_argument('-T', '--threads', dest='threads', default=2, type=int,
                        help='the number of threads used to find the bounds for vocabulary creation (default is 2)')
    parser.add_argument('--count-threshold', dest='count_threshold', default=None, type=int,
                        help='all tokens with a count less than this threshold will be used '
                             'only for alphabet generation in vocabulary creation, useful for very large corpus')
    parser.add_argument('--vocabulary', metavar='VOCABULARY_PATH', dest='vocabulary_path', default=None,
                        help='use the specified bpe vocabulary model instead of re-train a new one from scratch')
    parser.add_argument('--log', dest='log_file', default=None, help='detailed log file')

    parser.add_argument('--task', metavar='TASK', dest='task', default='mmt_translation',
                        help='task')
    parser.add_argument('--with-factors', dest='with_factors', action='store_true', default=False,
                        help='generate factors')

    args = parser.parse_args(argv)
    if args.debug and args.wdir is None:
        raise CLIArgsException(parser, '"--debug" options requires explicit working dir with "--working-dir"')
    return args


def main(argv=None):
    args = parse_args(argv)
    activity = DatagenActivity(args, wdir=args.wdir, log_file=args.log_file, delete_on_exit=not args.debug)
    activity.run()
