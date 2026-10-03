#include "mobileuiadapter.h"

#include <QAbstractItemView>
#include <QAbstractScrollArea>
#include <QAction>
#include <QApplication>
#include <QDockWidget>
#include <QDialog>
#include <QEvent>
#include <QFont>
#include <QGuiApplication>
#include <QIcon>
#include <QKeyEvent>
#include <QMainWindow>
#include <QMenu>
#include <QMenuBar>
#include <QScreen>
#include <QScroller>
#include <QSize>
#include <QSizePolicy>
#include <QStatusBar>
#include <QStyle>
#include <QToolBar>
#include <QToolButton>
#include <QWidget>

#include "sidebar.h"

namespace Digikam
{

namespace
{

class MobileApplicationFilter final : public QObject
{
public:
    explicit MobileApplicationFilter(QObject* parent)
        : QObject(parent)
    {
    }

protected:
    bool eventFilter(QObject* watched, QEvent* event) override
    {
        if ((event->type() == QEvent::Show) && watched)
        {
            if (QDialog* const dialog = qobject_cast<QDialog*>(watched))
            {
                // Desktop configuration/setup dialogs frequently assume a
                // large resizable window. On a phone/tablet give them the
                // available application surface instead of letting controls
                // fall outside the screen.
                if (dialog->isWindow())
                {
                    dialog->setWindowState(
                        dialog->windowState() | Qt::WindowMaximized);
                }
            }
        }

        return QObject::eventFilter(watched, event);
    }
};

QString mobileStyleSheet()
{
    return QStringLiteral(R"QSS(
        QMainWindow {
            background: palette(window);
        }

        QPushButton, QToolButton, QComboBox {
            min-height: 44px;
            min-width: 44px;
            padding: 6px 10px;
            font-size: 16px;
        }

        QLineEdit, QTextEdit, QPlainTextEdit, QSpinBox, QDoubleSpinBox {
            min-height: 44px;
            padding: 6px 10px;
            font-size: 16px;
        }

        QTreeView::item, QListView::item, QTableView::item {
            min-height: 44px;
        }

        QTabBar::tab {
            min-height: 44px;
            min-width: 72px;
            padding: 6px 10px;
        }

        QMenu {
            font-size: 16px;
        }

        QMenu::item {
            min-height: 44px;
            padding: 10px 22px;
        }

        QDialogButtonBox QPushButton {
            min-height: 48px;
            min-width: 96px;
        }

        QToolBar#DigikamMobileBottomBar {
            spacing: 0px;
            padding: 2px 0px;
            border-top: 1px solid palette(mid);
        }

        QToolBar#DigikamMobileBottomBar QToolButton {
            min-height: 62px;
            min-width: 64px;
            padding: 2px 8px;
            font-size: 13px;
        }
    )QSS");
}

QAction* addMobileAction(QToolBar* bar,
                         const QString& iconName,
                         const QString& text)
{
    QAction* const action = bar->addAction(QIcon::fromTheme(iconName), text);

    if (QToolButton* const button =
            qobject_cast<QToolButton*>(bar->widgetForAction(action)))
    {
        button->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
        button->setAutoRaise(false);
        button->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    }

    return action;
}

} // namespace

void MobileUiAdapter::prepareApplication(QApplication* app)
{
    if (!app)
    {
        return;
    }

    QFont f = app->font();

    if (f.pointSizeF() > 0.0)
    {
        f.setPointSizeF(qMax(11.0, f.pointSizeF()));
    }

    app->setFont(f);
    app->setStyleSheet(app->styleSheet() + mobileStyleSheet());

    // Install before digiKam's first-run assistant is created so initial
    // collection/database setup is usable on a phone as well.
    app->installEventFilter(new MobileApplicationFilter(app));
}

MobileUiAdapter* MobileUiAdapter::install(QMainWindow* window)
{
    if (!window)
    {
        return nullptr;
    }

    MobileUiAdapter* const adapter = new MobileUiAdapter(window);
    adapter->applyWindowChrome();
    adapter->applyTouchBehaviour();
    adapter->buildBottomNavigation();
    adapter->collapseSidebars();
    adapter->resizeActiveDrawer();

    window->installEventFilter(adapter);
    // Android Back is delivered to whichever child currently owns focus.
    // Observe application events as well so an open navigation drawer can
    // consume Back consistently before the app exits.
    qApp->installEventFilter(adapter);

    return adapter;
}

MobileUiAdapter::MobileUiAdapter(QMainWindow* window)
    : QObject(window),
      m_window(window)
{
}

void MobileUiAdapter::applyWindowChrome()
{
    // Use the complete application surface on phones/tablets while leaving
    // Android's system navigation/status handling to Qt and the OS.
    m_window->setWindowState(m_window->windowState() | Qt::WindowMaximized);
    m_window->setMinimumSize(QSize(320, 480));

    if (m_window->menuBar())
    {
        m_window->menuBar()->hide();
    }

    if (m_window->statusBar())
    {
        m_window->statusBar()->hide();
    }

    const QList<QToolBar*> toolBars = m_window->findChildren<QToolBar*>();

    for (QToolBar* const bar : toolBars)
    {
        bar->hide();
    }

    const QList<QDockWidget*> docks = m_window->findChildren<QDockWidget*>();

    for (QDockWidget* const dock : docks)
    {
        dock->hide();
    }
}

void MobileUiAdapter::applyTouchBehaviour()
{
    const QList<QAbstractScrollArea*> areas =
            m_window->findChildren<QAbstractScrollArea*>();

    for (QAbstractScrollArea* const area : areas)
    {
        if (area->viewport())
        {
            QScroller::grabGesture(area->viewport(),
                                   QScroller::TouchGesture);
        }

        area->setHorizontalScrollBarPolicy(Qt::ScrollBarAsNeeded);
        area->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    }

    const QList<QAbstractItemView*> itemViews =
            m_window->findChildren<QAbstractItemView*>();

    for (QAbstractItemView* const view : itemViews)
    {
        view->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
        view->setHorizontalScrollMode(QAbstractItemView::ScrollPerPixel);
    }
}

void MobileUiAdapter::buildBottomNavigation()
{
    m_bottomBar = new QToolBar(m_window);
    m_bottomBar->setObjectName(QStringLiteral("DigikamMobileBottomBar"));
    m_bottomBar->setMovable(false);
    m_bottomBar->setFloatable(false);
    m_bottomBar->setIconSize(QSize(28, 28));
    m_bottomBar->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);

    QAction* const browse = addMobileAction(
        m_bottomBar, QStringLiteral("view-grid"), tr("Browse"));
    QAction* const albums = addMobileAction(
        m_bottomBar, QStringLiteral("folder-pictures"), tr("Albums"));
    QAction* const people = addMobileAction(
        m_bottomBar, QStringLiteral("edit-image-face-show"), tr("People"));
    QAction* const search = addMobileAction(
        m_bottomBar, QStringLiteral("edit-find"), tr("Search"));
    QAction* const more = addMobileAction(
        m_bottomBar, QStringLiteral("application-menu"), tr("More"));

    connect(browse, &QAction::triggered,
            this, &MobileUiAdapter::collapseSidebars);

    connect(albums, &QAction::triggered,
            this, [this]()
            {
                activateSidebar(QStringLiteral("AlbumFolderView Sidebar"));
            });

    connect(people, &QAction::triggered,
            this, [this]()
            {
                // The face-management tab has used different object names
                // across digiKam releases. Prefer a dedicated People tab when
                // present and fall back to the tags sidebar, which still
                // exposes face/person tags in the 9.x desktop UI.
                if (!activateSidebar(QStringLiteral("People Sidebar")) &&
                    !activateSidebar(QStringLiteral("Face Tags Sidebar")) &&
                    !activateSidebar(QStringLiteral("Faces Sidebar")))
                {
                    activateSidebar(QStringLiteral("TagView Sidebar"));
                }
            });

    connect(search, &QAction::triggered,
            this, [this]()
            {
                activateSidebar(QStringLiteral("Search Sidebar"));
            });

    connect(more, &QAction::triggered,
            this, [this, more]()
            {
                showMoreMenu(m_bottomBar->widgetForAction(more));
            });

    m_window->addToolBar(Qt::BottomToolBarArea, m_bottomBar);
    m_bottomBar->show();
}

void MobileUiAdapter::collapseSidebars()
{
    const QList<Sidebar*> sidebars = m_window->findChildren<Sidebar*>();

    for (Sidebar* const sidebar : sidebars)
    {
        sidebar->shrink();
    }
}

bool MobileUiAdapter::activateSidebar(const QString& objectName)
{
    QWidget* const target =
            m_window->findChild<QWidget*>(objectName, Qt::FindChildrenRecursively);

    if (!target)
    {
        return false;
    }

    const QList<Sidebar*> sidebars = m_window->findChildren<Sidebar*>();

    for (Sidebar* const sidebar : sidebars)
    {
        if (sidebar->isAncestorOf(target))
        {
            // Phone layout: only one navigation drawer at a time.
            for (Sidebar* const other : sidebars)
            {
                if (other != sidebar)
                {
                    other->shrink();
                }
            }

            sidebar->setActiveTab(target);
            sidebar->expand();
            resizeActiveDrawer();
            return true;
        }
    }

    return false;
}

void MobileUiAdapter::showMoreMenu(QWidget* anchor)
{
    QMenu* const menu = new QMenu(m_window);
    menu->setAttribute(Qt::WA_DeleteOnClose);

    struct Entry
    {
        const char* label;
        const char* objectName;
        const char* icon;
    };

    const Entry entries[] =
    {
        {"Tags",       "TagView Sidebar",       "tag"},
        {"Dates",      "DateFolderView Sidebar","view-calendar-list"},
        {"Timeline",   "TimeLine Sidebar",      "player-time"},
        {"Similarity", "Fuzzy Search Sidebar",  "tools-wizard"},
        {"Map",        "GPS Search Sidebar",    "globe"}
    };

    for (const Entry& entry : entries)
    {
        if (m_window->findChild<QWidget*>(
                QString::fromLatin1(entry.objectName),
                Qt::FindChildrenRecursively))
        {
            QAction* const action =
                    menu->addAction(QIcon::fromTheme(QString::fromLatin1(entry.icon)),
                                    tr(entry.label));

            connect(action, &QAction::triggered,
                    this, [this, entry]()
                    {
                        activateSidebar(QString::fromLatin1(entry.objectName));
                    });
        }
    }

    if (m_window->menuBar() && !m_window->menuBar()->actions().isEmpty())
    {
        menu->addSeparator();

        // Preserve access to every original digiKam command without keeping
        // the desktop menu bar permanently visible on a phone.
        for (QAction* const action : m_window->menuBar()->actions())
        {
            menu->addAction(action);
        }
    }

    const QPoint globalPos = anchor
            ? anchor->mapToGlobal(QPoint(0, 0))
            : m_window->mapToGlobal(QPoint(m_window->width(), m_window->height()));

    const QSize hint = menu->sizeHint();
    menu->popup(QPoint(qMax(0, globalPos.x() - hint.width() + (anchor ? anchor->width() : 0)),
                       qMax(0, globalPos.y() - hint.height())));
}

void MobileUiAdapter::resizeActiveDrawer()
{
    const bool portrait = m_window->height() >= m_window->width();
    const int desired = portrait
            ? qRound(m_window->width() * 0.92)
            : qRound(m_window->width() * 0.46);

    const QList<Sidebar*> sidebars = m_window->findChildren<Sidebar*>();

    for (Sidebar* const sidebar : sidebars)
    {
        if (sidebar->isExpanded() && sidebar->splitter())
        {
            sidebar->splitter()->setSize(sidebar, qMax(240, desired));
        }
    }
}

bool MobileUiAdapter::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() == QEvent::KeyPress)
    {
        QKeyEvent* const keyEvent = static_cast<QKeyEvent*>(event);

        if ((keyEvent->key() == Qt::Key_Back) ||
            (keyEvent->key() == Qt::Key_Escape))
        {
            const QList<Sidebar*> sidebars =
                    m_window->findChildren<Sidebar*>();

            for (Sidebar* const sidebar : sidebars)
            {
                if (sidebar->isExpanded())
                {
                    collapseSidebars();
                    keyEvent->accept();
                    return true;
                }
            }
        }
    }

    if ((watched == m_window) &&
        ((event->type() == QEvent::Resize) ||
         (event->type() == QEvent::Show)))
    {
        resizeActiveDrawer();
    }

    return QObject::eventFilter(watched, event);
}

} // namespace Digikam
