"""Common utilities for local and remote clients."""

import re


class NotFound(Exception):
    pass

DEFAULT_IGNORED_PREFIXES = [
    '.',  # hidden Unix files
    '~$',  # Windows lock files
]

DEFAULT_IGNORED_SUFFIXES = [
    '~',  # editor buffers
    '.swp',  # vim swap files
    '.lock',  # some process use file locks
    '.LOCK',  # other locks
    '.tmp', # Ignore tmp files
    '.part',  # partially downloaded files
    'Thumbs.db',
]

BUFFER_SIZE = 1024 ** 2


def safe_filename(name, replacement=u'-'):
    """Replace invalid character in candidate filename"""
    return re.sub(ur'(/|\\|\*|:|\||"|<|>|\?)', replacement, name)
