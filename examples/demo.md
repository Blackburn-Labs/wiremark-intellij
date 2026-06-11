# Wiremark in Markdown

The Wiremark plugin renders fenced wireframe blocks in the IDE's Markdown
preview, the same way it renders Mermaid diagrams. Open this file and switch on
the Markdown preview to see the sketches below.

Both ` ```wireframe ` (canonical) and ` ```wiremark ` are accepted as the fence
info string -- use whichever you like.

## A login screen

```wireframe
Wireframe #login preset=mobile
  Stack column gap=2
    Typography h4 "Sign in"
    TextField "Email"
    TextField "Password" type=password
    Button "Sign in" contained fullWidth
    Typography caption "Forgot your password?"
```

## A settings panel

The `wiremark` fence label works identically:

```wiremark
Wireframe #settings
  AppBar
    Toolbar
      Icon arrow-back
      Typography h6 "Settings"
  List subheader="Account"
    ListItem "Profile"
    ListItem "Notifications"
    ListItem "Privacy"
  Divider
  List subheader="About"
    ListItem "Version 1.0.0"
    ListItem "Sign out"
```

Plain Markdown around the blocks renders as usual -- **bold**, _italic_,
[links](https://wiremark.dev), and lists all behave normally.
