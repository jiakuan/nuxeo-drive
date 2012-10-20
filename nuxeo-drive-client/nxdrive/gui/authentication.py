"""GUI prompt to bind a new server"""
from nxdrive.client import Unauthorized
from nxdrive.logging_config import get_logger

log = get_logger(__name__)

# Keep QT an optional dependency for now
QtGui, QDialog = None, None
try:
    from PySide import QtGui
    QDialog = QtGui.QDialog
except ImportError:
    log.warn("QT / PySide is not installed: GUI is disabled")
    pass


class Dialog(QDialog):
    """Dialog box to prompt the user for Server Bind credentials"""

    def __init__(self, fields_spec, title=None, fields_title=None,
                 callback=None, error_message=None):
        super(Dialog, self).__init__()
        self.create_authentication_box(fields_spec)
        self.callback = callback
        self.error_message = error_message
        buttonBox = QtGui.QDialogButtonBox(QtGui.QDialogButtonBox.Ok
                                           | QtGui.QDialogButtonBox.Cancel)
        buttonBox.accepted.connect(self.accept)
        buttonBox.rejected.connect(self.reject)

        mainLayout = QtGui.QVBoxLayout()
        mainLayout.addWidget(self.authentication_group_box)
        self.message_area = QtGui.QLabel()
        self.message_area.setWordWrap(True)
        mainLayout.addWidget(self.message_area)
        mainLayout.addWidget(buttonBox)
        self.setLayout(mainLayout)
        if title is not None:
            self.setWindowTitle(title)
        self.resize(600, -1)
        self.accepted = False

    def create_authentication_box(self, fields_spec):
        self.authentication_group_box = QtGui.QGroupBox()
        layout = QtGui.QGridLayout()
        self.fields = {}
        for i, spec in enumerate(fields_spec):
            label = QtGui.QLabel(spec['label'])
            line_edit = QtGui.QLineEdit()
            value = spec.get('value')
            if value is not None:
                line_edit.setText(value)
            if spec.get('is_password', False):
                line_edit.setEchoMode(QtGui.QLineEdit.Password)
            if spec.get('is_readonly', False):
                line_edit.setReadOnly(True)
            line_edit.textChanged.connect(self.clear_message)
            layout.addWidget(label, i + 1, 0)
            layout.addWidget(line_edit, i + 1, 1)
            self.fields[spec['id']] = line_edit

        self.authentication_group_box.setLayout(layout)

    def clear_message(self, *args, **kwargs):
        self.message_area.setText(None)

    def accept(self):
        if self.callback is not None:
            values = dict((id_, w.text()) for id_, w in self.fields.items())
            if not self.callback(values):
                print self.error_message
                if self.error_message is not None:
                    self.message_area.setText(self.error_message)
                    self.message_area.show()
                return
        self.accepted = True
        super(Dialog, self).accept()

    def reject(self):
        super(Dialog, self).reject()



def prompt_authentication(controller, local_folder, url=None, username=None,
                          is_url_readonly=False):
    """Prompt a QT dialog to ask for user credentials for binding a server"""
    if QtGui is None:
        # Qt / PySide is not installed
        log.error("QT / PySide is not installed:"
                  " use commandline options for binding a server.")
        return False

    # TODO: learn how to use QT i18n support to handle translation of labels
    field_specs = [
        {
            'id': 'url',
            'label': 'Nuxeo Server URL:',
            'value': url,
            'is_readonly': is_url_readonly,
        },
        {
            'id': 'username',
            'label': 'Username:',
            'value': username,
        },
        {
            'id': 'password',
            'label': 'Password:',
            'is_password': True,
        },
    ]
    def bind_server(values):
        try:
            controller.bind_server(
                local_folder, values['url'], values['username'],
                values['password'])
            return True
        except Unauthorized:
            return False
        # TODO: catch a new ServerUnreachable catching network isssues and
        # return a custom message

    QtGui.QApplication([])
    dialog = Dialog(field_specs, title="Nuxeo Drive - Authentication",
                    callback=bind_server,
                    error_message="Invalid credentials.")
    try:
        dialog.exec_()
    except:
        dialog.reject()
        raise
    return dialog.accepted

if __name__ == '__main__':
    from nxdrive.controller import Controller
    from nxdrive.controller import default_nuxeo_drive_folder
    ctl = Controller('/tmp')
    local_folder = default_nuxeo_drive_folder()
    print prompt_authentication(
        ctl, local_folder,
        url='http://localhost:8080/nuxeo',
        username='Administrator',
    )