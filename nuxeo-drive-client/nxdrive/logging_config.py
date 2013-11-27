"""Utilities to log nxdrive operations and failures"""

import logging
from logging.handlers import RotatingFileHandler
import os


TRACE = 5
logging.addLevelName(TRACE, 'TRACE')
logging.TRACE = TRACE


# Singleton logging context for each process.
# Alternatively we could use the setproctitle to handle the command name
# package and directly change the real process name but this requires to build
# a compiled extension under Windows...

_logging_context = dict()


def configure(log_filename, file_level='INFO', console_level='INFO',
              email_level='ERROR', smtp_log_conf_file=None,
              command_name=None, log_rotate_keep=3,
              log_rotate_max_bytes=100000000):

    _logging_context['command'] = command_name

    # convert string levels
    if hasattr(file_level, 'upper'):
        file_level = getattr(logging, file_level.upper())
    if hasattr(console_level, 'upper'):
        console_level = getattr(logging, console_level.upper())
    if hasattr(email_level, 'upper'):
        email_level = getattr(logging, email_level.upper())

    # find the minimum level to avoid filtering by the root logger itself:
    root_logger = logging.getLogger()
    min_level = min(file_level, console_level)
    root_logger.setLevel(min_level)

    # define a Handler for file based log with rotation
    log_filename = os.path.expanduser(log_filename)
    log_folder = os.path.dirname(log_filename)
    if not os.path.exists(log_folder):
        os.makedirs(log_folder)

    file_handler = RotatingFileHandler(
        log_filename, mode='a', maxBytes=log_rotate_max_bytes,
        backupCount=log_rotate_keep)
    file_handler.setLevel(file_level)

    # define a Handler which writes INFO messages or higher to the sys.stderr
    console_handler = logging.StreamHandler()
    console_handler.setLevel(console_level)

    # define the formatter
    formatter = logging.Formatter(
        "%(asctime)s %(process)d %(thread)d %(levelname)-8s %(name)-18s"
        " %(message)s"
    )

    # tell the handler to use this format
    console_handler.setFormatter(formatter)
    file_handler.setFormatter(formatter)

    # add the handler to the root logger and all descendants
    root_logger.addHandler(console_handler)
    root_logger.addHandler(file_handler)

    # define a Handler which sends ERROR messages to predefined email addresses
    if smtp_log_conf_file is not None:
        smtp_log_conf_file = str(smtp_log_conf_file)
    print 'SMTP configuration file: %s' % smtp_log_conf_file
    if os.path.exists(smtp_log_conf_file):
        config = {}
        execfile(smtp_log_conf_file, config)
        mailhost = config['mailhost']
        fromaddr = config['fromaddr']
        toaddrs = config['toaddrs']
        subject = config['subject']

        print 'SMTP configuration: ' \
              'mailhost=%s, ' \
              'fromaddr=%s, ' \
              'toaddrs=%s, ' \
              'subject=%s' % (mailhost, fromaddr, toaddrs, subject)
        email_handler = logging.handlers.SMTPHandler(mailhost=mailhost,
                                                     fromaddr=fromaddr,
                                                     toaddrs=toaddrs,
                                                     subject=subject)
        email_handler.setLevel(email_level)
        email_handler.setFormatter(formatter)
        root_logger.addHandler(email_handler)


def get_logger(name):
    logger = logging.getLogger(name)
    trace = lambda *args, **kwargs: logger.log(TRACE, *args, **kwargs)
    setattr(logger, 'trace', trace)
    return logger
